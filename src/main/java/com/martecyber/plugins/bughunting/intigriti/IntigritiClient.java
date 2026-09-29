package com.martecyber.plugins.bughunting.intigriti;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.tools.IntegrationClient;
import com.martecyber.plugins.bughunting.BugHuntingClient;
import com.martecyber.plugins.bughunting.PlatformApiException;
import com.martecyber.plugins.bughunting.ScopeImportItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intigriti Researcher API client (v1 BETA).
 *
 * Auth: Authorization: Bearer <api_token>
 *
 * Credentials map:
 *   api_token — Intigriti personal access token
 *
 * Programme handle format (as entered in Ares):
 *   {companyHandle}/{programHandle}   e.g. "majorleaguebaseball/mlbvdp"
 *   — visible in the programme URL on app.intigriti.com
 *
 * Flow:
 *   1. GET /external/researcher/v1/programs → list all accessible programmes
 *   2. Find programme matching companyHandle + programHandle → extract UUID
 *   3. GET /external/researcher/v1/programs/{uuid} → programme detail with domains
 *
 * Domain response (domains.content[]):
 *   endpoint          — target value
 *   type.id / value   — category: 1=URL, 2=Android, 3=Apple/iOS, 4=CIDR, 5=Device, 6=Other, 7=Wildcard
 *   tier.id / value   — bounty tier; id=5 = "No Bounty" (reward tier, not a scope flag)
 *   description       — optional notes
 *
 * Out-of-scope: confirmed via the researcher API's OpenAPI schema — there is no separate
 * out-of-scope list on this endpoint. Every entry in domains.content is in-scope by definition,
 * so every entry is imported as such (no "outOfScope" field/endpoint exists to fetch).
 *
 * Domains version: ProgramDetailViewModel.domains IS the current version object directly
 * ({id, createdAt, content: [...]}) — not a pointer to a separate "latest version" elsewhere.
 *
 * Ref: https://kb.intigriti.com/en/articles/8529303-intigriti-researcher-api
 *      https://api.intigriti.com/external/researcher/swagger/v1/swagger.json
 */
public class IntigritiClient implements BugHuntingClient, IntegrationClient {

    private static final Logger log = LoggerFactory.getLogger(IntigritiClient.class);
    private static final String BASE_URL = "https://api.intigriti.com";

    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public IntigritiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override public String platform() { return "intigriti"; }

    @Override
    public String credentialHelpText() {
        return "Generate a token at Intigriti → My Account → API.";
    }
    @Override public String supports()  { return "intigriti"; }

    @Override
    public void testConnection(String settingsJson, Map<String, String> credentials) throws Exception {
        get("/external/researcher/v1/programs", credentials);
    }

    /**
     * Fetches scope for the programme identified by "{companyHandle}/{programHandle}".
     *
     * Flow (confirmed via /external/researcher/swagger/v1/swagger.json):
     *   1. GET /v1/programs              → list, find UUID by compound handle
     *   2. GET /v1/programs/{id}         → programme detail; detail.domains IS the current
     *                                      version object ({id, createdAt, content: [...]})
     *                                      (403 if you haven't joined the programme on Intigriti)
     *   3. GET /v1/programs/{id}/domains/{versionId} → authoritative domain entries for that
     *                                      version. Response is ProgramDomainsViewModel =
     *                                      { "domains": {id, createdAt, content: [...]} } — an
     *                                      extra wrapper layer around the same version-object
     *                                      shape used in step 2, unlike detail.domains which IS
     *                                      that object directly. Easy to miss and silently
     *                                      produces zero scope items if unwrapped incorrectly.
     */
    @Override
    public List<ScopeImportItem> fetchScope(String programHandle, Map<String, String> credentials)
            throws Exception {
        String[] parts = programHandle.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Intigriti programme handle must be 'companyHandle/programHandle', got: " + programHandle);
        }
        String companyHandle = parts[0].trim();
        String handle        = parts[1].trim();

        // Step 1: resolve UUID via the programmes list
        String[] resolved = resolveIdAndCompound(companyHandle, handle, credentials);
        if (resolved == null) {
            throw new PlatformApiException("Intigriti", 404,
                "{\"title\":\"Programme '" + programHandle + "' not found. Check the handle or join the programme on Intigriti.\"}");
        }
        String programId       = resolved[0];
        String resolvedCompound = resolved[1];

        // Step 2: get programme detail — NOTE: 403 here means you need to JOIN the programme
        // on Intigriti first.
        JsonNode detail = get("/external/researcher/v1/programs/" + programId, credentials);
        JsonNode domainsVersion = detail.path("domains");
        String domainsVersionId = domainsVersion.path("id").asText(null);

        List<ScopeImportItem> items = new ArrayList<>();
        if (domainsVersionId != null) {
            // Step 3: fetch the authoritative content for that version (detail's embedded
            // content may be truncated/summarised for large scopes). Unlike detail.domains
            // (which IS the version object directly), this endpoint's response is
            // ProgramDomainsViewModel = { "domains": VersionViewModelOfListOfDomainViewModel }
            // — one extra wrapper layer, confirmed via swagger.json. Passing the raw response
            // straight to parseDomains() skipped this wrapper and always produced an empty
            // list, surfacing as a false "No domains found" error on every programme.
            JsonNode domainsResponse = get("/external/researcher/v1/programs/" + programId
                + "/domains/" + domainsVersionId, credentials);
            parseDomains(domainsResponse.path("domains"), resolvedCompound, items);
        } else {
            // Fallback: no version id present — use whatever content is embedded in detail directly.
            parseDomains(domainsVersion, resolvedCompound, items);
        }

        if (items.isEmpty()) {
            throw new PlatformApiException("Intigriti", 404,
                "{\"title\":\"No domains found for programme '" + programHandle + "'.\"}");
        }

        log.info("Intigriti fetchScope programme={} version={} items={}",
            programHandle, domainsVersionId, items.size());
        return items;
    }

    /**
     * Fetches the programme's rules-of-engagement testing requirements.
     *
     * Flow (confirmed via /external/researcher/swagger/v1/swagger.json):
     *   1. GET /v1/programs/{id}                                    → detail.rulesOfEngagement.id
     *      is the current RoE version id (same embedding pattern as detail.domains.id)
     *   2. GET /v1/programs/{id}/rules-of-engagements/{versionId}    → ProgramRulesOfEngagementViewModel:
     *      { rulesOfEngagement: { content: { testingRequirements: {
     *          intigritiMe, automatedTooling, userAgent, requestHeader } } } }
     *
     * Only userAgent/requestHeader are surfaced — they map onto Ares's own required_user_agent /
     * required_header project rules. automatedTooling/intigritiMe/safeHarbour aren't synced;
     * there's no equivalent concept in Ares yet.
     */
    @Override
    public Map<String, String> fetchTestingRequirements(String programHandle, Map<String, String> credentials)
            throws Exception {
        String[] parts = programHandle.split("/", 2);
        if (parts.length != 2) return Map.of();

        String[] resolved = resolveIdAndCompound(parts[0].trim(), parts[1].trim(), credentials);
        if (resolved == null) return Map.of();
        String programId = resolved[0];

        JsonNode detail = get("/external/researcher/v1/programs/" + programId, credentials);
        String roeVersionId = detail.path("rulesOfEngagement").path("id").asText(null);
        if (roeVersionId == null) return Map.of();

        JsonNode roe = get("/external/researcher/v1/programs/" + programId
            + "/rules-of-engagements/" + roeVersionId, credentials);
        JsonNode testingReq = roe.path("rulesOfEngagement").path("content").path("testingRequirements");

        Map<String, String> result = new HashMap<>();
        String userAgent = testingReq.path("userAgent").asText(null);
        if (userAgent != null && !userAgent.isBlank()) result.put("userAgent", userAgent);
        String requestHeader = testingReq.path("requestHeader").asText(null);
        if (requestHeader != null && !requestHeader.isBlank()) result.put("requestHeader", requestHeader);

        log.info("Intigriti fetchTestingRequirements programme={} userAgent={} requestHeader={}",
            programHandle, userAgent != null, requestHeader != null);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final int PROGRAMS_PAGE_SIZE = 500; // API max for GET /v1/programs?limit=

    /**
     * Iterates the programmes list (paginated via limit/offset — GET /v1/programs?limit=&offset=,
     * limit capped at 500 by the API) to find the UUID and compound handle. Without an explicit
     * limit this endpoint defaults to a much smaller page, silently missing any programme past
     * the first page — this is what previously caused false "programme not found" errors for
     * accounts with more than a handful of programmes.
     * Returns [uuid, compoundHandle] or null if not found.
     */
    private String[] resolveIdAndCompound(String companyHandle, String handle,
                                          Map<String, String> credentials) throws Exception {
        int offset = 0;
        while (true) {
            JsonNode list = get("/external/researcher/v1/programs?limit=" + PROGRAMS_PAGE_SIZE
                + "&offset=" + offset, credentials);
            JsonNode programs = list.isArray() ? list : list.path("records");
            if (programs == null || !programs.isArray() || programs.isEmpty()) return null;

            for (JsonNode p : programs) {
                String pHandle         = p.path("handle").asText("").trim();
                String pId             = p.path("id").asText(p.path("programId").asText(null));
                String webLink         = p.path("webLinks").path("detail").asText("");
                String compoundFromUrl = extractCompoundHandle(webLink);

                log.trace("Intigriti program: handle={} compound={} id={}", pHandle, compoundFromUrl, pId);

                boolean match = pHandle.equalsIgnoreCase(handle)
                    || (compoundFromUrl != null && compoundFromUrl.equalsIgnoreCase(companyHandle + "/" + handle));

                if (match && pId != null && !pId.isBlank()) {
                    String compound = compoundFromUrl != null ? compoundFromUrl : (companyHandle + "/" + handle);
                    return new String[]{pId, compound};
                }
            }

            if (programs.size() < PROGRAMS_PAGE_SIZE) return null; // last page, no match found
            offset += PROGRAMS_PAGE_SIZE;
        }
    }

    /**
     * Extracts "{companyHandle}/{programHandle}" from an Intigriti webLinks.detail URL.
     * URL patterns observed:
     *   https://app.intigriti.com/auth/dashboard?redirect=/programs/acme/acme-vdp/detail
     *   https://app.intigriti.com/researcher/programs/acme/acme-vdp/detail
     */
    private static String extractCompoundHandle(String url) {
        if (url == null || url.isBlank()) return null;
        // Look for /programs/ followed by two slug segments
        int idx = url.indexOf("/programs/");
        if (idx < 0) return null;
        String after = url.substring(idx + "/programs/".length());
        // Remove trailing path (e.g. "/detail") — keep first two slash-delimited segments
        String[] segs = after.split("/");
        if (segs.length >= 2) return segs[0] + "/" + segs[1];
        return null;
    }

    /** Parses a domains version node (may be the array itself or {content:[...]}). Every
     *  entry is in-scope — see the class doc for why there's no separate out-of-scope list. */
    private void parseDomains(JsonNode node, String programHandle, List<ScopeImportItem> items) {
        JsonNode arr = node.isArray() ? node : node.path("content");
        if (!arr.isArray()) return;

        for (JsonNode d : arr) {
            String value = d.path("endpoint").asText("").trim();
            if (value.isEmpty()) continue;

            // type is an object {id, value} — use numeric id for reliable mapping
            int    typeId    = d.path("type").path("id").asInt(-1);
            String typeValue = d.path("type").path("value").asText(null);
            String notes     = d.path("description").asText(null);

            String kind       = intigritiTypeToKind(typeId, typeValue, value);
            String externalId = programHandle + "::" + value;
            items.add(new ScopeImportItem(kind, value, externalId, notes, true, null, null, null));
        }
    }

    /**
     * Maps Intigriti type IDs to Ares scope kinds.
     *   1 = URL       → url / url_wildcard
     *   2 = Android   → android_app
     *   3 = Apple/iOS → ios_app
     *   4 = CIDR      → cidr
     *   5 = Device    → hardware
     *   6 = Other     → other
     *   7 = Wildcard  → domain_wildcard / url_wildcard
     */
    private static String intigritiTypeToKind(int typeId, String typeValue, String value) {
        return switch (typeId) {
            case 1 -> isWildcard(value) ? "url_wildcard" : "url";
            case 2 -> "android_app";
            case 3 -> "ios_app";
            case 4 -> "cidr";
            case 5 -> "hardware";
            case 7 -> isDomainLike(value) ? "domain_wildcard" : "url_wildcard";
            default -> {
                // Fallback: use the string value if id is unknown
                if (typeValue != null) yield intigritiTargetTypeToKindFallback(typeValue.toLowerCase(), value);
                yield "other";
            }
        };
    }

    private static String intigritiTargetTypeToKindFallback(String type, String value) {
        return switch (type) {
            case "url"     -> isWildcard(value) ? "url_wildcard" : "url";
            case "cidr"    -> "cidr";
            case "android" -> "android_app";
            case "ios"     -> "ios_app";
            case "device"  -> "hardware";
            default        -> "other"; // executable, other
        };
    }

    private static boolean isWildcard(String value) {
        return value != null && value.contains("*");
    }

    /** Heuristic: a "Wildcard" asset is domain-like when it has no URL scheme. */
    private static boolean isDomainLike(String value) {
        if (value == null) return false;
        return !value.contains("://");
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private JsonNode get(String path, Map<String, String> credentials) throws Exception {
        String token = credentials.getOrDefault("api_token", "");
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new PlatformApiException("Intigriti", resp.statusCode(), resp.body());
        return objectMapper.readTree(resp.body());
    }
}
