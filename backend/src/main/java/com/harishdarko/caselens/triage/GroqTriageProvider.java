package com.harishdarko.caselens.triage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class GroqTriageProvider implements TriageProvider {
    private static final String PROMPT_VERSION = "triage-v2";
    private static final int MAX_RESPONSE_CHARS = 65_536;
    /**
     * Total time one analyze call may spend on Groq, a rate-limit wait and the retry included. The worker Lambda times
     * out at 60 s and also needs time for the claim before the call, the rules fallback and the saves after it (see
     * WorkerTimingContractTest). Each request's timeout is cut to what is left of this budget.
     */
    static final Duration CALL_BUDGET = Duration.ofSeconds(45);
    static final Duration CONNECT_ALLOWANCE = Duration.ofSeconds(2);
    /** The least time a retry is worth: a wait that would leave less than this for the retry is not taken. */
    static final Duration MIN_RETRY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MIN_FIRST_TIMEOUT = Duration.ofSeconds(1);
    /**
     * Upper bound on the answer, reasoning included. A full answer at every schema maximum is about 1,700 tokens and a
     * typical one a few hundred, so this cuts off only a runaway answer, which then fails as unreadable.
     */
    static final int MAX_COMPLETION_TOKENS = 2_048;
    /** gpt-oss reasons before it answers; low effort spends far fewer tokens on a task this structured. */
    static final String REASONING_EFFORT = "low";
    private static final String NUMBER = "\\d+(?:\\.\\d+)?";
    private static final Pattern SECONDS = Pattern.compile(NUMBER);
    private static final Pattern GO_DURATION = Pattern.compile(
            "(?:(" + NUMBER + ")h)?(?:(" + NUMBER + ")m(?!s))?(?:(" + NUMBER + ")s)?(?:(" + NUMBER + ")ms)?");
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    private final IntSupplier retryDelayMillis;
    private final Duration callBudget;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final PromptBuilder promptBuilder = new PromptBuilder(PROMPT_VERSION);

    /** Waits between requests; a test passes one that records the wait instead of sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public GroqTriageProvider(URI endpoint, String apiKey, String model, HttpClient client,
            ObjectMapper mapper, Duration requestTimeout, IntSupplier retryDelayMillis) {
        this(endpoint, apiKey, model, client, mapper, requestTimeout, retryDelayMillis, CALL_BUDGET, System::nanoTime,
                Thread::sleep);
    }

    GroqTriageProvider(URI endpoint, String apiKey, String model, HttpClient client, ObjectMapper mapper,
            Duration requestTimeout, IntSupplier retryDelayMillis, Duration callBudget, LongSupplier nanoTime,
            Sleeper sleeper) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.apiKey = required(apiKey, "Groq API key");
        this.model = required(model, "Groq model");
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
        this.callBudget = Objects.requireNonNull(callBudget);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(redactedTicket);
        long started = nanoTime.getAsLong();
        HttpResponse<String> response = sendWithOneRetry(requestBody(request, redactedTicket), started);
        if (response.body().length() > MAX_RESPONSE_CHARS) {
            throw new ProviderCallException("Groq provider response was too large", "RESPONSE_TOO_LARGE");
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            String output = root.path("choices").path(0).path("message").path("content").asText(null);
            if (output == null || output.isBlank()) {
                throw new ProviderCallException("Groq provider returned no model output", "SCHEMA_FAILURE");
            }
            ProviderTriageResult result = new TriageResponseSchema(mapper, request.pack()).parse(output, model, PROMPT_VERSION);
            JsonNode usage = root.path("usage");
            return new ProviderCall(result, usageLong(usage, "prompt_tokens"), usageLong(usage, "completion_tokens"));
        } catch (ProviderCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderCallException("Groq provider returned invalid structured output", "SCHEMA_FAILURE", exception);
        }
    }

    private HttpRequest httpRequest(String body, Duration timeout) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /** The configured request timeout, cut to what is left of the call budget after the connect allowance. */
    private Duration attemptTimeout(long started) {
        Duration left = callBudget.minus(elapsed(started)).minus(CONNECT_ALLOWANCE);
        return left.compareTo(requestTimeout) < 0 ? left : requestTimeout;
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - started));
    }

    /**
     * One request and at most one retry. A 429 is retried after the wait Groq asks for (retry-after, else the token or
     * request reset time) when that wait and a useful retry still fit in the call budget; otherwise the call ends at
     * once as HTTP_429, so the worker can leave the job for SQS to deliver again later instead of holding the Lambda.
     * A 429 without any of those headers, and a 5xx, are retried after a short jitter. 413 (a request larger than the
     * token limit) and other 4xx are not retried.
     */
    private HttpResponse<String> sendWithOneRetry(String body, long started) {
        Duration timeout = attemptTimeout(started);
        if (timeout.compareTo(MIN_FIRST_TIMEOUT) < 0) timeout = MIN_FIRST_TIMEOUT;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> response = send(body, timeout);
            int status = response.statusCode();
            if (status >= 200 && status < 300) return response;
            if (attempt == 0 && (status == 429 || status >= 500)) {
                Duration wait = status == 429 ? rateLimitWait(response.headers()) : null;
                if (wait != null && !fitsInBudget(started, wait)) {
                    throw new ProviderCallException("Groq provider rate limit outlasts the call budget", "HTTP_429");
                }
                pause(wait != null ? wait : jitter());
                timeout = attemptTimeout(started);
                // Too little budget left for a useful retry: fail now so the worker still has time to finish.
                if (timeout.equals(requestTimeout) || timeout.compareTo(MIN_RETRY_TIMEOUT) >= 0) continue;
            }
            throw failure(status, response.body());
        }
        throw new ProviderCallException("Groq provider request failed", "TRANSIENT_FAILURE");
    }

    private HttpResponse<String> send(String body, Duration timeout) {
        try {
            return client.send(httpRequest(body, timeout), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ProviderCallException("Groq provider request failed", "PROVIDER_IO", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException("Groq provider request was interrupted", "INTERRUPTED", exception);
        }
    }

    private boolean fitsInBudget(long started, Duration wait) {
        return elapsed(started).plus(wait).plus(CONNECT_ALLOWANCE).plus(MIN_RETRY_TIMEOUT).compareTo(callBudget) <= 0;
    }

    /**
     * A strict-schema model whose answer breaks the schema gets a 400 with error code json_validate_failed: that is an
     * unreadable answer, not a provider fault. Only the error code is read from the body, never its message or the
     * failed generation, which can quote the ticket.
     */
    private ProviderCallException failure(int status, String body) {
        if (status == 401 || status == 403) {
            return new ProviderCallException("Groq provider request was rejected", "AUTHENTICATION_FAILURE");
        }
        if (status == 400 && "json_validate_failed".equals(errorCode(body))) {
            return new ProviderCallException("Groq provider answer did not match the response schema", "SCHEMA_FAILURE");
        }
        return new ProviderCallException("Groq provider request failed", "HTTP_" + status);
    }

    private String errorCode(String body) {
        if (body == null || body.length() > MAX_RESPONSE_CHARS) return null;
        try {
            return mapper.readTree(body).path("error").path("code").asText(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The wait a 429 asks for: retry-after first, then the token reset, then the request reset; null when absent. */
    static Duration rateLimitWait(HttpHeaders headers) {
        for (String name : List.of("retry-after", "x-ratelimit-reset-tokens", "x-ratelimit-reset-requests")) {
            Optional<Duration> wait = headers.firstValue(name).flatMap(GroqTriageProvider::parseDuration);
            if (wait.isPresent()) return wait.get();
        }
        return null;
    }

    /** Seconds ("3", "1.5") as retry-after sends them, or a Go duration ("7.66s", "2m59.56s", "250ms") as the reset headers do. */
    static Optional<Duration> parseDuration(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String text = value.strip();
        if (SECONDS.matcher(text).matches()) return Optional.of(millis(new BigDecimal(text), 1_000));
        Matcher matcher = GO_DURATION.matcher(text);
        if (!matcher.matches()) return Optional.empty();
        long[] unitMillis = {3_600_000, 60_000, 1_000, 1};
        Duration total = Duration.ZERO;
        boolean any = false;
        for (int group = 1; group <= unitMillis.length; group++) {
            if (matcher.group(group) == null) continue;
            any = true;
            total = total.plus(millis(new BigDecimal(matcher.group(group)), unitMillis[group - 1]));
        }
        return any ? Optional.of(total) : Optional.empty();
    }

    private static Duration millis(BigDecimal amount, long unitMillis) {
        return Duration.ofMillis(amount.multiply(BigDecimal.valueOf(unitMillis)).setScale(0, RoundingMode.CEILING)
                .longValueExact());
    }

    private Duration jitter() {
        return Duration.ofMillis(Math.max(0, Math.min(250, retryDelayMillis.getAsInt())));
    }

    private void pause(Duration wait) {
        try {
            if (!wait.isZero()) sleeper.sleep(wait.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException("Groq provider request was interrupted", "INTERRUPTED", exception);
        }
    }

    private String requestBody(TriageRequest request, RedactedTicket redactedTicket) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        if (isReasoningModel()) root.put("reasoning_effort", REASONING_EFFORT);
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", promptBuilder.systemInstruction()
                + " Return exactly one JSON object with these fields: category, urgency, slaRisk, sentiment, summary, evidence, policyIds, explanation, recommendedActions, suggestedReply, and warnings."
                + " Use only policy identifiers permitted by the response schema; return an empty policyIds array when none apply."
                + " Allowed policyIds: " + String.join(", ", request.pack().policies().ids().stream().sorted().toList()) + ".");
        messages.addObject().put("role", "user").put("content", promptBuilder.build(request, redactedTicket));
        root.set("response_format", responseFormat(new TriageResponseSchema(mapper, request.pack())));
        try {
            return mapper.writeValueAsString(root);
        } catch (JacksonException exception) {
            throw new ProviderCallException("Groq provider request could not be encoded", "REQUEST_ENCODING", exception);
        }
    }

    private ObjectNode responseFormat(TriageResponseSchema responseSchema) {
        if (!supportsStrictSchema()) return mapper.createObjectNode().put("type", "json_object");
        ObjectNode responseFormat = mapper.createObjectNode().put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "caselens_triage");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", responseSchema.schema());
        return responseFormat;
    }

    private boolean supportsStrictSchema() {
        return isReasoningModel();
    }

    /** The gpt-oss models: they support the strict schema and take reasoning_effort. */
    private boolean isReasoningModel() {
        return "openai/gpt-oss-20b".equals(model) || "openai/gpt-oss-120b".equals(model);
    }

    private Long usageLong(JsonNode usage, String name) {
        return usage.has(name) ? usage.get(name).asLong() : null;
    }

    @Override public String providerName() { return "groq"; }
    @Override public String modelVersion() { return model; }
    @Override public String promptVersion() { return PROMPT_VERSION; }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
