package com.gendaz.leads.osm.enrichment;

import com.gendaz.leads.osm.discovery.OsmCandidate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enrichment oficial em Java (port do comportamento util do Python).
 * Ordem: tags diretas -> website oficial -> pagina de contato oficial ->
 * hub oficial ligado pelo OSM/site. Nunca usa busca Google/Bing, nunca
 * pesquisa pelo nome, nunca inventa handle/telefone.
 */
public class OfficialContactEnrichmentService {

    private final OfficialWebsiteFetcher fetcher;
    private final String countryCode;
    private final Map<String, String> pageCache = new ConcurrentHashMap<>();

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

    public EnrichedContact enrich(OsmCandidate candidate) {
        PhoneResolver.Phone phone = PhoneResolver.fromTags(candidate.tags(), countryCode);
        InstagramResolver.InstagramHandle instagram = InstagramResolver.fromTags(candidate.tags());
        boolean directPhone = phone != null;
        boolean directInstagram = instagram != null;
        String website = fetcher.primaryWebsite(candidate.tags());
        StringBuilder officialText = new StringBuilder();
        if (candidate.name() != null) officialText.append(candidate.name()).append(' ');

        if (website != null && (phone == null || instagram == null)) {
            OfficialWebsiteFetcher.FetchResult res = fetcher.fetch(website);
            if (res.html() != null) {
                officialText.append(stripTags(res.html())).append(' ');
                if (phone == null) {
                    phone = PhoneResolver.fromText(res.html(), "OSM_WEBSITE", res.url(), countryCode);
                }
                if (instagram == null) {
                    instagram = InstagramResolver.fromHtml(res.html(), "WEBSITE_INSTAGRAM", res.url());
                }
                // Pagina de contato oficial (mesmo host, max 2 urls com keywords).
                if (phone == null || instagram == null) {
                    for (String contactUrl : findContactPageUrls(res.html(), res.url())) {
                        OfficialWebsiteFetcher.FetchResult cres = fetcher.fetch(contactUrl);
                        if (cres.html() == null) continue;
                        officialText.append(stripTags(cres.html())).append(' ');
                        if (phone == null) {
                            phone = PhoneResolver.fromText(cres.html(), "CONTACT_PAGE", cres.url(), countryCode);
                        }
                        if (instagram == null) {
                            instagram = InstagramResolver.fromHtml(cres.html(), "CONTACT_PAGE_INSTAGRAM", cres.url());
                        }
                        if (phone != null && instagram != null) break;
                    }
                }
            }
        }
        String text = officialText.toString();
        if (text.length() > 20000) text = text.substring(0, 20000);
        return new EnrichedContact(phone, instagram, website, text, directPhone, directInstagram);
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
