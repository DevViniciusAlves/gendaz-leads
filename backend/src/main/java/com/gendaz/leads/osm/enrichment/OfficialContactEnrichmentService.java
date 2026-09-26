package com.gendaz.leads.osm.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.gendaz.leads.osm.discovery.OsmCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enrichment oficial em Java (port do comportamento util do Python).
 * Ordem: tags diretas -> website oficial -> pagina de contato oficial ->
 * JSON-LD sameAs -> hub oficial ligado pelo OSM/site -> social oficial ligado.
 * Nunca usa busca Google/Bing, nunca pesquisa pelo nome, nunca inventa handle/telefone.
 * Thread-safe: fetcher + caches ConcurrentHashMap, sem estado mutavel por chamada.
 */
public class OfficialContactEnrichmentService {

    private static final Set<String> HUB_HOSTS = Set.of(
            "linktr.ee", "beacons.ai", "bio.site", "lnk.bio", "campsite.bio",
            "solo.to", "linkbio.co", "msha.ke", "taplink.cc", "linktree");

    private final OfficialWebsiteFetcher fetcher;
    private final String countryCode;
    private final Map<String, HubResult> hubCache = new ConcurrentHashMap<>();
    private final Map<String, String> socialCache = new ConcurrentHashMap<>();

    public final AtomicLong contactPagesFetched = new AtomicLong();
    public final AtomicLong contactHubCandidates = new AtomicLong();
    public final AtomicLong contactHubSuccess = new AtomicLong();
    public final AtomicLong sameAsLinksFound = new AtomicLong();
    public final AtomicLong socialCandidates = new AtomicLong();
    public final AtomicLong socialSuccess = new AtomicLong();
    public final AtomicLong websiteCandidates = new AtomicLong();

    public OfficialContactEnrichmentService(OfficialWebsiteFetcher fetcher, String countryCode) {
        this.fetcher = fetcher;
        this.countryCode = countryCode;
    }

    public record EnrichedContact(
            PhoneResolver.Phone phone,
            InstagramResolver.InstagramHandle instagram,
            String website,
            String officialText,
            boolean directPhone,
            boolean directInstagram) {}

    record HubResult(PhoneResolver.Phone phone, InstagramResolver.InstagramHandle instagram, String text) {}

    /** Fast reject: sem nenhum canal oficial nao ha fonte autorizada. Zero HTTP. */
    public static boolean hasOfficialContactChannel(JsonNode tags) {
        if (tags == null || !tags.isObject()) return false;
        for (String k : new String[]{"phone", "contact:phone", "mobile", "contact:mobile",
                "contact:whatsapp", "whatsapp", "contact:sms", "sms",
                "website", "contact:website", "url",
                "instagram", "contact:instagram",
                "facebook", "contact:facebook", "telegram", "contact:telegram"}) {
            JsonNode v = tags.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        return false;
    }

    public static boolean hasDirectSocialOrPhone(JsonNode tags) {
        return hasOfficialContactChannel(tags);
    }

    public EnrichedContact enrich(OsmCandidate candidate) {
        PhoneResolver.Phone phone = PhoneResolver.fromTags(candidate.tags(), countryCode);
        InstagramResolver.InstagramHandle instagram = InstagramResolver.fromTags(candidate.tags());
        boolean directPhone = phone != null;
        boolean directInstagram = instagram != null;
        String website = fetcher.primaryWebsite(candidate.tags());
        StringBuilder officialText = new StringBuilder();
        if (candidate.name() != null) officialText.append(candidate.name()).append(' ');

        if (website != null && (phone == null || instagram == null)) {
            websiteCandidates.incrementAndGet();
            OfficialWebsiteFetcher.FetchResult res = fetcher.fetch(website);
            if (res.html() != null) {
                officialText.append(stripTags(res.html())).append(' ');
                if (phone == null) {
                    phone = PhoneResolver.fromText(res.html(), "OSM_WEBSITE", res.url(), countryCode);
                }
                if (instagram == null) {
                    instagram = InstagramResolver.fromHtml(res.html(), "OSM_WEBSITE", res.url());
                }
                // D. JSON-LD sameAs: somente Instagram/Facebook/Hub controlado.
                List<String> sameAs = extractSameAs(res.html());
                if (!sameAs.isEmpty()) sameAsLinksFound.addAndGet(sameAs.size());
                for (String sameUrl : sameAs) {
                    if (phone != null && instagram != null) break;
                    String lower = sameUrl.toLowerCase(Locale.ROOT);
                    if (lower.contains("instagram.com") && instagram == null) {
                        String h = InstagramResolver.normalize(sameUrl);
                        if (h != null) {
                            instagram = new InstagramResolver.InstagramHandle(h, "OSM_WEBSITE_SAMEAS_SOCIAL", res.url());
                            socialSuccess.incrementAndGet();
                        }
                    } else if (isHubUrl(sameUrl)) {
                        contactHubCandidates.incrementAndGet();
                        HubResult hub = fetchHub(sameUrl);
                        if (hub != null) {
                            if (hub.text() != null) officialText.append(hub.text()).append(' ');
                            if (phone == null && hub.phone() != null) {
                                phone = hub.phone();
                                contactHubSuccess.incrementAndGet();
                            }
                            if (instagram == null && hub.instagram() != null) {
                                instagram = hub.instagram();
                                contactHubSuccess.incrementAndGet();
                            }
                        }
                    } else if ((lower.contains("facebook.com") || lower.contains("fb.com")) && phone == null) {
                        // F. Social oficial ligado: busca 1 nivel, sem pesquisa por nome.
                        socialCandidates.incrementAndGet();
                        OfficialWebsiteFetcher.FetchResult sres = fetchSocialCached(sameUrl);
                        if (sres.html() != null) {
                            officialText.append(stripTags(sres.html())).append(' ');
                            if (phone == null) phone = PhoneResolver.fromText(sres.html(), "OSM_DIRECT_SOCIAL", sres.url(), countryCode);
                            if (instagram == null) instagram = InstagramResolver.fromHtml(sres.html(), "OSM_DIRECT_SOCIAL", sres.url());
                            if (phone != null || instagram != null) socialSuccess.incrementAndGet();
                        }
                    }
                }
                // C. Pagina de contato oficial (mesmo host, max 2 urls com keywords).
                if (phone == null || instagram == null) {
                    for (String contactUrl : findContactPageUrls(res.html(), res.url())) {
                        contactPagesFetched.incrementAndGet();
                        OfficialWebsiteFetcher.FetchResult cres = fetcher.fetch(contactUrl);
                        if (cres.html() == null) continue;
                        officialText.append(stripTags(cres.html())).append(' ');
                        if (phone == null) {
                            phone = PhoneResolver.fromText(cres.html(), "OSM_WEBSITE_CONTACT_PAGE", cres.url(), countryCode);
                        }
                        if (instagram == null) {
                            instagram = InstagramResolver.fromHtml(cres.html(), "OSM_WEBSITE_CONTACT_PAGE", cres.url());
                        }
                        if (phone != null && instagram != null) break;
                    }
                }
                // E. Contact hubs oficiais linkados pelo website.
                if (phone == null || instagram == null) {
                    for (String hubUrl : findHubUrls(res.html(), res.url())) {
                        if (phone != null && instagram != null) break;
                        contactHubCandidates.incrementAndGet();
                        HubResult hub = fetchHub(hubUrl);
                        if (hub == null) continue;
                        if (hub.text() != null) officialText.append(hub.text()).append(' ');
                        if (phone == null && hub.phone() != null) {
                            phone = hub.phone();
                            contactHubSuccess.incrementAndGet();
                        }
                        if (instagram == null && hub.instagram() != null) {
                            instagram = hub.instagram();
                            contactHubSuccess.incrementAndGet();
                        }
                    }
                }
            }
        }
        // E2. Hub direto na tag OSM (ex: website = linktr.ee/xxx).
        if ((phone == null || instagram == null) && website != null && isHubUrl(website)) {
            contactHubCandidates.incrementAndGet();
            HubResult hub = fetchHub(website);
            if (hub != null) {
                if (hub.text() != null) officialText.append(hub.text()).append(' ');
                if (phone == null && hub.phone() != null) {
                    phone = hub.phone();
                    contactHubSuccess.incrementAndGet();
                }
                if (instagram == null && hub.instagram() != null) {
                    instagram = hub.instagram();
                    contactHubSuccess.incrementAndGet();
                }
            }
        }
        // F2. Social direto na tag OSM (facebook/contact:facebook) como fonte oficial.
        if ((phone == null || instagram == null) && candidate.tags() != null) {
            String socialUrl = directSocialUrl(candidate.tags());
            if (socialUrl != null) {
                socialCandidates.incrementAndGet();
                OfficialWebsiteFetcher.FetchResult sres = fetchSocialCached(socialUrl);
                if (sres.html() != null) {
                    officialText.append(stripTags(sres.html())).append(' ');
                    if (phone == null) phone = PhoneResolver.fromText(sres.html(), "OSM_DIRECT_SOCIAL", sres.url(), countryCode);
                    if (instagram == null) instagram = InstagramResolver.fromHtml(sres.html(), "OSM_DIRECT_SOCIAL", sres.url());
                    if (phone != null || instagram != null) socialSuccess.incrementAndGet();
                }
            }
        }
        String text = officialText.toString();
        if (text.length() > 20000) text = text.substring(0, 20000);
        return new EnrichedContact(phone, instagram, website, text, directPhone, directInstagram);
    }

    private OfficialWebsiteFetcher.FetchResult fetchSocialCached(String url) {
        String norm = OfficialWebsiteFetcher.normalizeUrl(url);
        if (norm == null) return new OfficialWebsiteFetcher.FetchResult(url, null, "INVALID_URL");
        String cachedHtml = socialCache.get(norm);
        if (cachedHtml != null) return new OfficialWebsiteFetcher.FetchResult(norm, cachedHtml, "OK_CACHED");
        OfficialWebsiteFetcher.FetchResult r = fetcher.fetch(norm);
        if (r.html() != null) socialCache.put(norm, r.html());
        return r;
    }

    HubResult fetchHub(String url) {
        String norm = OfficialWebsiteFetcher.normalizeUrl(url);
        if (norm == null) return null;
        HubResult cached = hubCache.get(norm);
        if (cached != null) return cached;
        OfficialWebsiteFetcher.FetchResult r = fetcher.fetch(norm);
        if (r.html() == null) {
            HubResult empty = new HubResult(null, null, null);
            hubCache.put(norm, empty);
            return empty;
        }
        PhoneResolver.Phone p = PhoneResolver.fromText(r.html(), "OSM_WEBSITE_CONTACT_HUB", r.url(), countryCode);
        InstagramResolver.InstagramHandle ig = InstagramResolver.fromHtml(r.html(), "OSM_WEBSITE_CONTACT_HUB", r.url());
        HubResult out = new HubResult(p, ig, stripTags(r.html()));
        hubCache.put(norm, out);
        return out;
    }

    static boolean isHubUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI u = java.net.URI.create(url);
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            return HUB_HOSTS.contains(host);
        } catch (Exception e) {
            return false;
        }
    }

    private static String directSocialUrl(JsonNode tags) {
        for (String k : new String[]{"contact:facebook", "facebook", "contact:telegram", "telegram"}) {
            JsonNode n = tags.get(k);
            if (n != null && !n.isNull() && !n.asText().isBlank()) {
                String v = n.asText().trim();
                if (v.startsWith("http")) return v;
            }
        }
        return null;
    }

    static List<String> extractSameAs(String html) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher script = java.util.regex.Pattern.compile(
                "(?is)<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>").matcher(html);
        while (script.find()) {
            String json = script.group(1).trim();
            if (json.isBlank()) continue;
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                JsonNode root = om.readTree(json);
                List<JsonNode> nodes = new ArrayList<>();
                if (root.isArray()) root.forEach(nodes::add);
                else nodes.add(root);
                for (JsonNode node : nodes) {
                    JsonNode sameAs = node.get("sameAs");
                    if (sameAs == null) continue;
                    if (sameAs.isTextual()) out.add(sameAs.asText());
                    else if (sameAs.isArray()) sameAs.forEach(n -> {
                        if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText().trim());
                    });
                }
            } catch (Exception ignored) {}
        }
        return out.stream().filter(u -> u.startsWith("http")).distinct().limit(10).toList();
    }

    static List<String> findHubUrls(String html, String baseUrl) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        try {
            java.net.URI base = java.net.URI.create(baseUrl);
            while (m.find() && out.size() < 3) {
                String href = m.group(1);
                try {
                    String abs = base.resolve(href).toString();
                    if (isHubUrl(abs) && !out.contains(abs)) out.add(abs);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static java.util.List<String> findContactPageUrls(String html, String baseUrl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        String[] keywords = {"contato", "contact", "fale-conosco", "atendimento", "whatsapp"};
        try {
            java.net.URI base = java.net.URI.create(baseUrl);
            while (m.find() && out.size() < 2) {
                String href = m.group(1);
                String lower = href.toLowerCase(java.util.Locale.ROOT);
                boolean kw = false;
                for (String k : keywords) {
                    if (lower.contains(k)) { kw = true; break; }
                }
                if (!kw) continue;
                try {
                    String abs = base.resolve(href).toString();
                    java.net.URI u = java.net.URI.create(abs);
                    if (u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost())) {
                        out.add(abs);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String stripTags(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 20000 ? s.substring(0, 20000) : s;
    }
}
