import unittest
from unittest.mock import patch, MagicMock
import json
import socket
import osm_catalog_sync as sync


def make_row(osm_type='node', osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags=None, country_code='br', address=None, website=None, email=None, instagram=None):
    normalized = sync.normalize_name(name) if name else None
    t = tags if tags is not None else {}
    # if tags contains shop etc, but also ensure name tag? row_tags just json
    # For row_is_commercial we need tags dict
    row = {
        'sync_run_id': 1,
        'region_id': 2,
        'osm_type': osm_type,
        'osm_id': osm_id,
        'business_name': name,
        'normalized_name': normalized,
        'latitude': lat,
        'longitude': lon,
        'address': address,
        'city': 'Cuiaba',
        'state': 'MT',
        'country': 'Brasil',
        'country_code': country_code,
        'phone': phone,
        'email': email,
        'website': website,
        'instagram': instagram,
        'tags': json.dumps(t, ensure_ascii=False),
        'source_timestamp': None,
    }
    return row


class TestNormalizePhone(unittest.TestCase):
    def test_br_10_digits(self):
        self.assertEqual(sync.normalize_phone_for_catalog('6599991111', 'br'), '556599991111')

    def test_br_11_digits(self):
        self.assertEqual(sync.normalize_phone_for_catalog('65999991111', 'br'), '5565999991111')

    def test_br_with_55(self):
        self.assertEqual(sync.normalize_phone_for_catalog('+55 65 99999-1111', 'br'), '5565999991111')
        self.assertEqual(sync.normalize_phone_for_catalog('5565999991111', 'br'), '5565999991111')

    def test_br_without_ddd_rejected(self):
        self.assertIsNone(sync.normalize_phone_for_catalog('999991111', 'br'))
        self.assertIsNone(sync.normalize_phone_for_catalog('9999-1111', 'br'))

    def test_br_yes_rejected(self):
        self.assertIsNone(sync.normalize_phone_for_catalog('yes', 'br'))

    def test_br_invalid_ddd(self):
        self.assertIsNone(sync.normalize_phone_for_catalog('0599991111', 'br'))

    def test_br_duplicate_55(self):
        self.assertIsNone(sync.normalize_phone_for_catalog('5555999991111', 'br'))

    def test_br_semicolon(self):
        self.assertEqual(sync.normalize_phone_for_catalog('yes; 65999991111', 'br'), '5565999991111')

    def test_foreign(self):
        self.assertEqual(sync.normalize_phone_for_catalog('+1 650 555 1234', 'us'), '16505551234')

    def test_has_valid_ddd(self):
        self.assertTrue(sync.has_valid_brazilian_ddd('6599991111'))
        self.assertTrue(sync.has_valid_brazilian_ddd('65999991111'))
        self.assertFalse(sync.has_valid_brazilian_ddd('0599991111'))
        self.assertFalse(sync.has_valid_brazilian_ddd('99991111'))


class TestCanMerge(unittest.TestCase):
    def test_commercial_plus_contact_only_within_50m(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber', 'name': 'Barbearia Teste'})
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste', 'contact:phone': '+5565888887777'})
        self.assertTrue(sync.can_merge_rows(a, b, 50.0))

    def test_same_name_over_50m_no_merge(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='+5565999991111', tags={'shop': 'barber'})
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.7, lon=-56.2, phone='+5565888887777', tags={'shop': 'barber'})
        # Both commercial same type different location, same phone? No, phone differs, website empty => should not merge
        # But to test distance condition, use commercial+contact-only far
        c = make_row(osm_id=4, name='Barbearia Teste', lat=-15.7, lon=-56.2, phone='+5565888887777', tags={'name': 'Barbearia Teste'})
        self.assertFalse(sync.can_merge_rows(a, c, 50.0))

    def test_two_commercial_same_type_close_no_phone_match_no_merge(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='+5565999991111', tags={'shop': 'barber'})
        b = make_row(osm_id=2, name='Barbearia Teste', lat=-15.6001, lon=-56.1001, phone='+5565888887777', tags={'shop': 'barber'})
        self.assertFalse(sync.can_merge_rows(a, b, 50.0))

    def test_two_commercial_different_type_can_merge(self):
        a = make_row(osm_type='node', osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='+5565999991111', tags={'shop': 'barber'})
        b = make_row(osm_type='way', osm_id=1, name='Barbearia Teste', lat=-15.6001, lon=-56.1001, phone='+5565888887777', tags={'shop': 'barber'})
        self.assertTrue(sync.can_merge_rows(a, b, 50.0))

    def test_same_phone_can_merge(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='+5565999991111', tags={'shop': 'barber'})
        b = make_row(osm_id=2, name='Barbearia Teste', lat=-15.6001, lon=-56.1001, phone='65999991111', tags={'shop': 'barber'})
        self.assertTrue(sync.can_merge_rows(a, b, 50.0))

    def test_same_website_can_merge(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='+5565999991111', tags={'shop': 'barber'}, website='https://example.com')
        b = make_row(osm_id=2, name='Barbearia Teste', lat=-15.6001, lon=-56.1001, phone='+5565888887777', tags={'shop': 'barber'}, website='https://example.com')
        self.assertTrue(sync.can_merge_rows(a, b, 50.0))


class TestCluster(unittest.TestCase):
    def test_cluster_merge_contact_only(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'})
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste'})
        rows = [a, b]
        clusters = sync.cluster_rows(rows, 50.0)
        self.assertEqual(len(clusters), 1)
        self.assertEqual(len(clusters[0]), 2)

    def test_cluster_not_merge_far(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'})
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.7, lon=-56.2, phone='+5565888887777', tags={'name': 'Barbearia Teste'})
        clusters = sync.cluster_rows(rows=[a, b], radius_meters=50.0)
        self.assertEqual(len(clusters), 2)


class TestBuildQualified(unittest.TestCase):
    def test_commercial_without_phone_not_published(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'})
        cluster = [a]
        # mock enrichment to avoid network: ensure website enrichment returns no phone
        with patch.object(sync, 'fetch_website_contact_result', return_value=sync.WebsiteContactResult(None, None, None, 'NO_PHONE')):
            self.assertIsNone(sync.build_qualified_row(cluster))

    def test_commercial_with_phone_published(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='65999991111', tags={'shop': 'barber'})
        result = sync.build_qualified_row([a])
        self.assertIsNotNone(result)
        self.assertEqual(result['phone'], '5565999991111')

    def test_contact_only_alone_not_published(self):
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste'})
        self.assertIsNone(sync.build_qualified_row([b]))

    def test_commercial_plus_contact_only_merged(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'}, address='Rua A')
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste', 'contact:phone': '+5565888887777'}, website='https://example.com')
        result = sync.build_qualified_row([a, b])
        self.assertIsNotNone(result)
        self.assertEqual(result['phone'], '5565888887777')
        self.assertEqual(result['website'], 'https://example.com')
        self.assertEqual(result['osm_type'], 'node')
        self.assertEqual(result['osm_id'], 1)

    def test_two_commercial_same_type_no_merge_separate_clusters(self):
        # Already tested via qualify
        pass


class TestBuildCatalog(unittest.TestCase):
    def test_commercial_without_phone_is_kept_in_catalog(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'})
        rows = [a]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], None)
        self.assertEqual(stats['catalog_published'], 1)
        self.assertEqual(stats['without_phone'], 1)
        self.assertEqual(stats['with_phone'], 0)

    def test_commercial_with_direct_phone_is_kept(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone='65999991111', tags={'shop': 'barber'})
        rows = [a]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], '5565999991111')
        self.assertEqual(stats['catalog_published'], 1)
        self.assertEqual(stats['with_phone'], 1)
        self.assertEqual(stats['without_phone'], 0)

    def test_commercial_with_website_phone_is_enriched(self):
        a = make_row(osm_id=1, name='Loja Web', phone=None, website='https://example.com', tags={'shop': 'barber'})
        html = '<a href="https://wa.me/5565999991111">wa</a>'
        with patch.object(sync, 'fetch_public_html_result', return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                catalog, stats = sync.build_catalog_rows([a], 50.0)
                self.assertEqual(len(catalog), 1)
                self.assertEqual(catalog[0]['phone'], '5565999991111')
                self.assertEqual(stats['enriched_website_whatsapp'], 1)

    def test_commercial_with_website_without_phone_is_kept_with_null_phone(self):
        a = make_row(osm_id=1, name='Loja Sem Phone Web', phone=None, website='https://example.com', tags={'shop': 'barber'})
        html = '<html>nothing</html>'
        with patch.object(sync, 'fetch_public_html_result', return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                catalog, stats = sync.build_catalog_rows([a], 50.0)
                self.assertEqual(len(catalog), 1)
                self.assertEqual(catalog[0]['phone'], None)
                self.assertEqual(stats['catalog_published'], 1)
                self.assertEqual(stats['without_phone'], 1)

    def test_commercial_without_official_channel_is_kept_with_null_phone(self):
        a = make_row(osm_id=1, name='Loja Sem Canal', phone=None, website=None, tags={'shop': 'barber'})
        catalog, stats = sync.build_catalog_rows([a], 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], None)
        self.assertEqual(stats['catalog_published'], 1)
        self.assertEqual(stats['without_phone'], 1)

    def test_contact_only_object_is_not_published_as_business(self):
        b = make_row(osm_id=4, name='Contact Only', lat=-15.6, lon=-56.1, phone='+5565888887777', tags={'name': 'Contact Only'})
        rows = [b]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 0)
        self.assertEqual(stats['discarded_no_commercial'], 1)

    def test_contact_only_object_can_merge_into_commercial(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'}, address='Rua A')
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste', 'contact:phone': '+5565888887777'}, website='https://example.com')
        rows = [a, b]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], '5565888887777')
        self.assertEqual(stats['merged_clusters'], 1)

    def test_companion_phone_is_inherited_safely(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'})
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste', 'contact:phone': '+5565888887777'})
        rows = [a, b]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], '5565888887777')

    def test_brand_website_does_not_qualify_branch(self):
        a = make_row(osm_id=1, name='Loja Marca', phone=None, website=None, tags={'shop': 'barber', 'brand:website': 'https://marca.com'})
        rows = [a]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], None)
        self.assertEqual(stats['brand_website_seen'], 1)

    def test_operator_website_does_not_qualify_branch(self):
        a = make_row(osm_id=1, name='Loja Operador', phone=None, website=None, tags={'shop': 'barber', 'operator:website': 'https://operadora.com'})
        rows = [a]
        catalog, stats = sync.build_catalog_rows(rows, 50.0)
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]['phone'], None)
        self.assertEqual(stats['operator_website_seen'], 1)


class TestQualify(unittest.TestCase):
    def test_qualify_merges_and_discards(self):
        a = make_row(osm_id=1, name='Barbearia Teste', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'barber'})
        b = make_row(osm_id=4, name='Barbearia Teste', lat=-15.6002, lon=-56.1002, phone='+5565888887777', tags={'name': 'Barbearia Teste'})
        c = make_row(osm_id=2, name='Barbearia Teste', lat=-15.7, lon=-56.2, phone='+5565999991111', tags={'shop': 'barber'})
        rows = [a, b, c]
        qualified, stats = sync.qualify_staging_rows(rows, 50.0)
        # a+b should merge -> 1 qualified, c far -> separate cluster with phone -> qualified => total 2
        self.assertEqual(len(qualified), 2)
        self.assertEqual(stats['merged_clusters'], 1)

    def test_contact_only_discarded(self):
        b = make_row(osm_id=4, name='Contact Only', lat=-15.6, lon=-56.1, phone='+5565888887777', tags={'name': 'Contact Only'})
        rows = [b]
        qualified, stats = sync.qualify_staging_rows(rows, 50.0)
        self.assertEqual(len(qualified), 0)
        self.assertEqual(stats['discarded_no_commercial'], 1)

    def test_commercial_no_phone_discarded(self):
        a = make_row(osm_id=1, name='Sem Phone', lat=-15.6, lon=-56.1, phone=None, tags={'shop': 'beauty'})
        rows = [a]
        qualified, stats = sync.qualify_staging_rows(rows, 50.0)
        self.assertEqual(len(qualified), 0)
        self.assertEqual(stats['discarded_no_phone'], 1)

    def test_distance_and_tags(self):
        self.assertTrue(sync.row_is_commercial(make_row(tags={'shop': 'beauty'})))
        self.assertFalse(sync.row_is_commercial(make_row(tags={'name': 'foo'})))
        a = make_row(lat=-15.6, lon=-56.1)
        b = make_row(lat=-15.6002, lon=-56.1002)
        self.assertLess(sync.distance_meters(a, b), 50)
        c = make_row(lat=-15.7, lon=-56.2)
        self.assertGreater(sync.distance_meters(a, c), 50)

    def test_stable_key(self):
        a = make_row(osm_type='node', osm_id=5)
        b = make_row(osm_type='way', osm_id=1)
        c = make_row(osm_type='relation', osm_id=1)
        self.assertLess(sync.stable_osm_key(a), sync.stable_osm_key(b))
        self.assertLess(sync.stable_osm_key(b), sync.stable_osm_key(c))

    def test_merge_tags(self):
        base = make_row(osm_id=1, tags={'shop': 'barber', 'name': 'Barbearia Teste'})
        companion = make_row(osm_id=4, tags={'contact:phone': '+5565888887777', 'website': 'https://example.com'})
        merged = sync.merge_tags(base, [base, companion])
        self.assertEqual(merged['shop'], 'barber')
        self.assertEqual(merged['contact:phone'], '+5565888887777')

    def test_row_tags(self):
        row = {'tags': json.dumps({'shop': 'barber'})}
        self.assertEqual(sync.row_tags(row), {'shop': 'barber'})
        row2 = {'tags': {'shop': 'barber'}}
        self.assertEqual(sync.row_tags(row2), {'shop': 'barber'})
        row3 = {'tags': 'invalid'}
        self.assertEqual(sync.row_tags(row3), {})


# ===== New website enrichment tests per spec PASSO 19 =====

class TestWebsiteNormalization(unittest.TestCase):
    def test_normalize_none(self):
        self.assertIsNone(sync.normalize_website_url(None))
        self.assertIsNone(sync.normalize_website_url(''))
        self.assertIsNone(sync.normalize_website_url('   '))

    def test_add_https(self):
        url = sync.normalize_website_url('example.com')
        self.assertEqual(url, 'https://example.com')

    def test_keep_https(self):
        url = sync.normalize_website_url('https://example.com/path')
        self.assertEqual(url, 'https://example.com/path')

    def test_invalid(self):
        # no hostname
        self.assertIsNone(sync.normalize_website_url('http://'))
        self.assertIsNone(sync.normalize_website_url('://example'))


class TestSsrfGuard(unittest.TestCase):
    def test_is_public_ip_private_blocked(self):
        self.assertFalse(sync.is_public_ip('127.0.0.1'))
        self.assertFalse(sync.is_public_ip('10.0.0.1'))
        self.assertFalse(sync.is_public_ip('192.168.1.10'))
        self.assertFalse(sync.is_public_ip('172.16.5.4'))
        self.assertFalse(sync.is_public_ip('169.254.169.254'))
        self.assertFalse(sync.is_public_ip('::1'))
        self.assertTrue(sync.is_public_ip('8.8.8.8'))
        self.assertTrue(sync.is_public_ip('93.184.216.34'))

    def test_safe_url_blocks_private(self):
        with patch('socket.getaddrinfo') as mock_getaddr:
            # private ip should be unsafe
            mock_getaddr.return_value = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', 443))]
            self.assertFalse(sync.is_safe_public_url('http://127.0.0.1/'))
            mock_getaddr.return_value = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('10.0.0.1', 443))]
            self.assertFalse(sync.is_safe_public_url('http://10.0.0.1/'))
            mock_getaddr.return_value = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('192.168.1.1', 80))]
            self.assertFalse(sync.is_safe_public_url('http://192.168.1.1/'))
            mock_getaddr.return_value = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('169.254.169.254', 80))]
            self.assertFalse(sync.is_safe_public_url('http://169.254.169.254/'))
            mock_getaddr.return_value = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('8.8.8.8', 443))]
            self.assertTrue(sync.is_safe_public_url('https://example.com/'))

    def test_localhost_blocked(self):
        self.assertFalse(sync.is_safe_public_url('http://localhost/'))
        self.assertFalse(sync.is_safe_public_url('http://LOCALHOST/'))


class TestExtraction(unittest.TestCase):
    def test_wame(self):
        html = '<a href="https://wa.me/5565999991111">chat</a>'
        phone = sync._extract_phone_from_html(html, 'br')
        self.assertEqual(phone, '5565999991111')

    def test_api_whatsapp(self):
        html = 'https://api.whatsapp.com/send?phone=5565999991111&text=hi'
        phone = sync._extract_phone_from_html(html, 'br')
        self.assertEqual(phone, '5565999991111')

    def test_web_whatsapp(self):
        html = 'https://web.whatsapp.com/send?phone=5565999992222'
        phone = sync._extract_phone_from_html(html, 'br')
        self.assertEqual(phone, '5565999992222')

    def test_tel_link(self):
        html = '<a href="tel:+55 65 9999-1111">call</a>'
        phone = sync._extract_phone_from_html(html, 'br')
        self.assertEqual(phone, '556599991111')

    def test_generic_phone(self):
        html = 'Ligue: (65) 99999-3333'
        phone = sync._extract_phone_from_html(html, 'br')
        self.assertEqual(phone, '5565999993333')

    def test_website_no_phone(self):
        html = '<html><body>No phone here <p>hello</p></body></html>'
        phone = sync._extract_phone_from_html(html, 'br')
        self.assertIsNone(phone)


class TestQualifyWithWebsite(unittest.TestCase):
    def test_phone_direct_no_website_call(self):
        a = make_row(osm_id=10, name='Loja Com Phone', phone='65999991111', tags={'shop': 'barber'})
        with patch.object(sync, 'fetch_website_contact_result') as mock_fetch:
            qualified, stats = sync.qualify_staging_rows([a], 50.0)
            mock_fetch.assert_not_called()
            self.assertEqual(len(qualified), 1)
            self.assertEqual(qualified[0]['phone'], '5565999991111')
            self.assertEqual(stats['qualified_direct_phone'], 1)

    def test_companion_phone_no_website_call(self):
        a = make_row(osm_id=1, name='Loja X', phone=None, tags={'shop': 'barber'})
        b = make_row(osm_id=99, name='Loja X', lat=-15.6001, lon=-56.1001, phone='65999992222', tags={'name': 'Loja X'})
        with patch.object(sync, 'fetch_website_contact_result') as mock_fetch:
            qualified, stats = sync.qualify_staging_rows([a, b], 50.0)
            mock_fetch.assert_not_called()
            self.assertEqual(len(qualified), 1)
            self.assertEqual(stats['qualified_companion_phone'], 1)

    def test_website_whatsapp_enrichment(self):
        a = make_row(osm_id=1, name='Loja Web', phone=None, website='https://example.com', tags={'shop': 'barber'})
        html = '<a href="https://wa.me/5565999991111">wa</a>'
        with patch.object(sync, 'fetch_public_html_result', return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 1)
                self.assertEqual(qualified[0]['phone'], '5565999991111')
                self.assertEqual(stats['qualified_website_phone'], 1)
                self.assertEqual(stats['website_candidates'], 1)
                self.assertEqual(stats['website_phone_found'], 1)

    def test_website_without_phone_discarded(self):
        a = make_row(osm_id=1, name='Loja Sem Phone Web', phone=None, website='https://example.com', tags={'shop': 'barber'})
        html = '<html>nothing</html>'
        with patch.object(sync, 'fetch_public_html_result', return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 0)
                self.assertEqual(stats['discarded_no_phone'], 1)
                self.assertEqual(stats['website_no_phone'], 1)

    def test_ssrf_127_blocked(self):
        a = make_row(osm_id=1, name='Loja Blocked', phone=None, website='http://127.0.0.1', tags={'shop': 'barber'})
        # is_safe_public_url will block via mocked getaddrinfo
        with patch('socket.getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', 80))]):
            qualified, stats = sync.qualify_staging_rows([a], 50.0)
            self.assertEqual(len(qualified), 0)
            self.assertEqual(stats['website_unsafe'], 1)

    def test_ssrf_localhost_blocked(self):
        a = make_row(osm_id=1, name='Loja Localhost', phone=None, website='http://localhost', tags={'shop': 'barber'})
        qualified, stats = sync.qualify_staging_rows([a], 50.0)
        self.assertEqual(len(qualified), 0)
        self.assertEqual(stats['website_unsafe'], 1)

    def test_ssrf_10_blocked(self):
        a = make_row(osm_id=1, name='Loja Private', phone=None, website='http://10.0.0.1/page', tags={'shop': 'barber'})
        with patch('socket.getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('10.0.0.1', 80))]):
            qualified, stats = sync.qualify_staging_rows([a], 50.0)
            self.assertEqual(len(qualified), 0)
            self.assertEqual(stats['website_unsafe'], 1)

    def test_ssrf_metadata_169_blocked(self):
        a = make_row(osm_id=1, name='Loja Meta', phone=None, website='http://169.254.169.254/latest', tags={'shop': 'barber'})
        with patch('socket.getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('169.254.169.254', 80))]):
            qualified, stats = sync.qualify_staging_rows([a], 50.0)
            self.assertEqual(len(qualified), 0)
            self.assertEqual(stats['website_unsafe'], 1)

    def test_redirect_public_to_private_blocked(self):
        # Simulate fetch that redirects to private IP: we mock fetch_public_html to emulate logic
        # Instead we test is_safe_public_url blocking and fetch returning None on redirect
        a = make_row(osm_id=1, name='Loja Redirect', phone=None, website='https://example.com', tags={'shop': 'barber'})
        # First fetch returns html with no phone, contact page logic will try second URL that is private
        # We'll patch fetch_public_html side effect: homepage returns html with contact link to private host
        homepage_html = '<a href="/contato">contato</a>'
        contact_html_should_not_be_fetched = None
        def fake_fetch(url, redirect_count=0, _retried=False):
            if url == 'https://example.com':
                return sync.FetchResult(homepage_html, url, 'OK', 200)
            if url == 'https://example.com/contato':
                # should be blocked before fetch: our code checks is_safe_public_url before fetch
                # we make is_safe_public_url return False for this contact url
                return sync.FetchResult(None, url, 'UNSAFE_URL', None)
            return sync.FetchResult(None, url, 'FETCH_FAILED', None)
        # patch is_safe_public_url to allow homepage but block contact
        original_safe = sync.is_safe_public_url
        def fake_safe(url):
            if 'contato' in url:
                return False
            return original_safe(url) if 'example.com' not in url else True
        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', side_effect=fake_safe):
                # also need _find_contact_page_urls to produce contato link
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                # homepage no phone, contact blocked => discarded
                self.assertEqual(len(qualified), 0)
                self.assertEqual(stats['website_no_phone'], 1)

    def test_contact_page_same_host_recover(self):
        a = make_row(osm_id=1, name='Loja Contato Page', phone=None, website='https://example.com', tags={'shop': 'barber'})
        homepage_html = '<a href="/contato">Contato</a><p>sem telefone</p>'
        contact_html = '<a href="tel:+55 65 99999-1111">call</a>'
        def fake_fetch(url, redirect_count=0, _retried=False):
            if url == 'https://example.com':
                return sync.FetchResult(homepage_html, url, 'OK', 200)
            if url == 'https://example.com/contato':
                return sync.FetchResult(contact_html, url, 'OK', 200)
            return sync.FetchResult(None, url, 'FETCH_FAILED', None)
        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 1)
                self.assertEqual(qualified[0]['phone'], '5565999991111')

    def test_cache_prevents_duplicate_fetch(self):
        # Two clusters sharing same website should fetch once
        a1 = make_row(osm_id=1, name='Loja A', lat=-15.6, lon=-56.1, phone=None, website='https://example.com', tags={'shop': 'barber'})
        a2 = make_row(osm_id=2, name='Loja B', lat=-15.65, lon=-56.15, phone=None, website='https://example.com', tags={'shop': 'barber'})
        # Different names, so separate clusters
        html = '<a href="https://wa.me/5565999991111">wa</a>'
        call_count = {'count': 0}
        def counting_fetch(url, cc='br', *args, **kwargs):
            call_count['count'] += 1
            return sync.WebsiteContactResult('5565999991111', None, None, 'FOUND_PHONE')
        # Patch fetch_website_contact_result instead of fetch_public_html to count per unique URL
        with patch.object(sync, 'fetch_website_contact_result', side_effect=counting_fetch):
            qualified, stats = sync.qualify_staging_rows([a1, a2], 50.0)
            # should have called fetch once per unique website (1)
            self.assertEqual(call_count['count'], 1)
            self.assertEqual(len(qualified), 2)

    def test_worker_pool_limited(self):
        # Create many website candidates to ensure pool respects max workers env
        rows = []
        for i in range(10):
            rows.append(make_row(osm_id=100+i, name=f'Loja {i}', lat=-15.6 + i*0.001, lon=-56.1 + i*0.001, phone=None, website=f'https://example{i}.com', tags={'shop': 'barber'}))
        # Mock fetch to return simple phone quickly
        def fake_result(nurl, cc, *args, **kwargs):
            return sync.WebsiteContactResult('5565999991111', None, None, 'FOUND_PHONE')
        max_workers_seen = {}
        original_executor = sync.ThreadPoolExecutor
        def fake_executor(*args, **kwargs):
            workers = kwargs.get('max_workers', args[0] if args else None)
            max_workers_seen['workers'] = workers
            return original_executor(*args, **kwargs)
        with patch.object(sync, 'ThreadPoolExecutor', side_effect=fake_executor):
            # temporarily set env to 2 and reload? Instead directly patch constant
            with patch.object(sync, 'WEBSITE_MAX_WORKERS', 2):
                with patch.object(sync, 'fetch_website_contact_result', side_effect=fake_result):
                    # Need is_safe mock true
                    with patch.object(sync, 'is_safe_public_url', return_value=True):
                        qualified, stats = sync.qualify_staging_rows(rows, 50.0)
                        self.assertIn('workers', max_workers_seen)
                        self.assertLessEqual(max_workers_seen['workers'], 2)
                        self.assertEqual(len(qualified), 10)


# ===== Full OSM option B tests =====

class TestDirectPhoneEvidence(unittest.TestCase):
    def test_contact_whatsapp_source(self):
        tags = {'contact:whatsapp': '+55 65 99999-9999'}
        ev = sync.extract_direct_phone_evidence(tags, 'br')
        self.assertIsNotNone(ev)
        self.assertEqual(ev.phone, '5565999999999')
        self.assertEqual(ev.source_type, 'DIRECT_OSM_WHATSAPP')

    def test_phone_parens(self):
        tags = {'phone': '(65) 3333-4444'}
        ev = sync.extract_direct_phone_evidence(tags, 'br')
        self.assertIsNotNone(ev)
        self.assertEqual(ev.phone, '556533334444')
        self.assertEqual(ev.source_type, 'DIRECT_OSM_PHONE')

    def test_contact_mobile(self):
        tags = {'contact:mobile': '65 99999-9999'}
        ev = sync.extract_direct_phone_evidence(tags, 'br')
        self.assertIsNotNone(ev)
        self.assertEqual(ev.phone, '5565999999999')
        self.assertEqual(ev.source_type, 'DIRECT_OSM_MOBILE')

    def test_contact_sms_is_not_whatsapp(self):
        tags = {'contact:sms': '65 99999-9999'}
        ev = sync.extract_direct_phone_evidence(tags, 'br')
        self.assertIsNotNone(ev)
        self.assertEqual(ev.source_type, 'DIRECT_OSM_SMS')

    def test_semicolon_second_value(self):
        tags = {'phone': 'invalid;+55 65 99999-9999'}
        ev = sync.extract_direct_phone_evidence(tags, 'br')
        self.assertIsNotNone(ev)
        self.assertEqual(ev.phone, '5565999999999')

    def test_sms_qualifies_with_sms_source(self):
        row = make_row(osm_id=21, name='Loja SMS', phone='65 99999-9999',
                       tags={'shop': 'barber', 'contact:sms': '65 99999-9999'})
        qualified, stats = sync.qualify_staging_rows([row], 50.0)
        self.assertEqual(len(qualified), 1)
        self.assertEqual(stats['qualified_direct_sms'], 1)
        self.assertEqual(stats['qualified_direct_phone'], 1)

    def test_whatsapp_qualifies_with_whatsapp_source(self):
        row = make_row(osm_id=22, name='Loja Wpp', phone='+55 65 99999-9999',
                       tags={'shop': 'barber', 'contact:whatsapp': '+55 65 99999-9999'})
        qualified, stats = sync.qualify_staging_rows([row], 50.0)
        self.assertEqual(len(qualified), 1)
        self.assertEqual(stats['qualified_direct_whatsapp'], 1)


class TestJsonLd(unittest.TestCase):
    def test_localbusiness_telephone(self):
        html = ('<script type="application/ld+json">'
                '{"@type":"LocalBusiness","telephone":"+55 65 99999-9999"}'
                '</script>')
        self.assertEqual(sync.extract_jsonld_phone(html, 'br'), '5565999999999')

    def test_contact_point(self):
        html = ('<script type="application/ld+json">'
                '{"@type":"Organization","contactPoint":{"@type":"ContactPoint","telephone":"+55 65 99999-9999"}}'
                '</script>')
        self.assertEqual(sync.extract_jsonld_phone(html, 'br'), '5565999999999')

    def test_graph(self):
        html = ('<script type="application/ld+json">'
                '{"@graph":[{"@type":"LocalBusiness","telephone":"+55 65 99999-9999"}]}'
                '</script>')
        self.assertEqual(sync.extract_jsonld_phone(html, 'br'), '5565999999999')

    def test_malformed_ignored(self):
        html = '<script type="application/ld+json">{invalid json</script>'
        self.assertEqual(sync.extract_jsonld_blocks(html), [])
        self.assertIsNone(sync.extract_jsonld_phone(html, 'br'))

    def test_jsonld_enrichment_source(self):
        html = ('<script type="application/ld+json">'
                '{"@type":"LocalBusiness","telephone":"+55 65 99999-9999"}'
                '</script>')
        phone, kind = sync._extract_phone_with_source(html, 'br')
        self.assertEqual(phone, '5565999999999')
        self.assertEqual(kind, 'JSONLD_PHONE')
        with patch.object(sync, 'fetch_public_html_result',
                          return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                res = sync.fetch_website_contact_result('https://example.com', 'br')
                self.assertEqual(res.status, 'FOUND_PHONE')
                self.assertEqual(res.phone, '5565999999999')
                self.assertEqual(res.source_type, 'OSM_WEBSITE_JSONLD_PHONE')

    def test_jsonld_qualifies(self):
        a = make_row(osm_id=31, name='Loja JSONLD', phone=None, website='https://example.com',
                     tags={'shop': 'barber'})
        html = ('<script type="application/ld+json">'
                '{"@type":"LocalBusiness","telephone":"+55 65 99999-9999"}'
                '</script>')
        with patch.object(sync, 'fetch_public_html_result',
                          return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 1)
                self.assertEqual(stats['qualified_website_jsonld'], 1)


class TestMicrodata(unittest.TestCase):
    def test_meta_itemprop(self):
        html = '<meta itemprop="telephone" content="+55 65 99999-9999">'
        self.assertEqual(sync.extract_microdata_phone(html, 'br'), '5565999999999')

    def test_anchor_itemprop_tel(self):
        html = '<a itemprop="telephone" href="tel:+5565999999999">call</a>'
        self.assertEqual(sync.extract_microdata_phone(html, 'br'), '5565999999999')

    def test_microdata_qualifies(self):
        a = make_row(osm_id=32, name='Loja Micro', phone=None, website='https://example.com',
                     tags={'shop': 'barber'})
        html = '<meta itemprop="telephone" content="+55 65 99999-9999">'
        with patch.object(sync, 'fetch_public_html_result',
                          return_value=sync.FetchResult(html, 'https://example.com', 'OK', 200)):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 1)
                self.assertEqual(stats['qualified_website_microdata'], 1)


class TestContactHub(unittest.TestCase):
    def test_hub_follow_recovers(self):
        home = '<a href="https://linktr.ee/barbeariax">link</a>'
        hub = '<a href="https://wa.me/5565999999999">wa</a>'

        def fake_fetch(url, redirect_count=0, _retried=False):
            if 'linktr.ee' in url:
                return sync.FetchResult(hub, url, 'OK', 200)
            return sync.FetchResult(home, url, 'OK', 200)

        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                res = sync.fetch_website_contact_result('https://example.com', 'br')
                self.assertEqual(res.phone, '5565999999999')
                self.assertEqual(res.source_type, 'OSM_WEBSITE_CONTACT_HUB')

    def test_random_domain_not_followed(self):
        home = '<a href="https://random-domain.example/page">link</a>'

        def fake_fetch(url, redirect_count=0, _retried=False):
            return sync.FetchResult(home, url, 'OK', 200)

        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                res = sync.fetch_website_contact_result('https://example.com', 'br')
                self.assertIsNone(res.phone)
                self.assertEqual(res.status, 'NO_PHONE')

    def test_hub_cache_single_get(self):
        home = '<a href="https://linktr.ee/shared">link</a>'
        hub = '<a href="https://wa.me/5565999999999">wa</a>'
        calls = {'hub': 0}

        def fake_fetch(url, redirect_count=0, _retried=False):
            if 'linktr.ee' in url:
                calls['hub'] += 1
                return sync.FetchResult(hub, url, 'OK', 200)
            return sync.FetchResult(home, url, 'OK', 200)

        a1 = make_row(osm_id=41, name='Loja Hub A', phone=None, website='https://a.example.com',
                      tags={'shop': 'barber'})
        a2 = make_row(osm_id=42, name='Loja Hub B', phone=None, website='https://b.example.com',
                      tags={'shop': 'barber'})
        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a1, a2], 50.0)
                self.assertEqual(len(qualified), 2)
                self.assertEqual(calls['hub'], 1)
                self.assertEqual(stats['qualified_contact_hub'], 2)


class TestSameAs(unittest.TestCase):
    def test_sameas_classification(self):
        blocks = [{'sameAs': ['https://instagram.com/barbeariax',
                              'https://linktr.ee/barbeariax',
                              'https://random-domain.example/x']}]
        urls = sync.extract_same_as_urls(blocks)
        self.assertEqual(len(urls), 3)
        kinds = [sync.classify_external_contact_url(u) for u in urls]
        self.assertIn('INSTAGRAM', kinds)
        self.assertIn('CONTACT_HUB', kinds)
        self.assertIn('OTHER', kinds)

    def test_sameas_other_not_followed(self):
        self.assertEqual(sync.classify_external_contact_url('https://random-domain.example/x'), 'OTHER')
        self.assertFalse(sync.is_contact_hub_url('https://random-domain.example/x'))


class TestSocialFetch(unittest.TestCase):
    def _social_row(self, osm_id, name):
        return make_row(osm_id=osm_id, name=name, phone=None, website=None,
                        tags={'shop': 'barber', 'contact:instagram': 'barbeariax'})

    def test_direct_social_recovers(self):
        a = self._social_row(51, 'Loja Social A')
        html = '<a href="https://wa.me/5565999999999">wa</a>'

        def fake_fetch(url, redirect_count=0, _retried=False):
            return sync.FetchResult(html, url, 'OK', 200)

        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 1)
                self.assertEqual(stats['qualified_social_public'], 1)
                self.assertEqual(stats['social_phone_found'], 1)

    def test_social_403_blocked(self):
        a = self._social_row(52, 'Loja Social B')

        def fake_fetch(url, redirect_count=0, _retried=False):
            return sync.FetchResult(None, url, 'HTTP_403', 403)

        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 0)
                self.assertEqual(stats['social_blocked'], 1)

    def test_social_429_blocked_no_aggressive_retry(self):
        a = self._social_row(53, 'Loja Social C')
        calls = {'n': 0}

        def fake_fetch(url, redirect_count=0, _retried=False):
            calls['n'] += 1
            return sync.FetchResult(None, url, 'HTTP_429', 429)

        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a], 50.0)
                self.assertEqual(len(qualified), 0)
                self.assertEqual(calls['n'], 1)
                self.assertEqual(stats['social_blocked'], 1)

    def test_social_cache_single_get(self):
        a1 = self._social_row(54, 'Loja Social D')
        a2 = make_row(osm_id=55, name='Loja Social E', phone=None, website=None,
                      tags={'shop': 'beauty', 'contact:instagram': 'barbeariax'})
        html = '<a href="https://wa.me/5565999999999">wa</a>'
        calls = {'n': 0}

        def fake_fetch(url, redirect_count=0, _retried=False):
            calls['n'] += 1
            return sync.FetchResult(html, url, 'OK', 200)

        with patch.object(sync, 'fetch_public_html_result', side_effect=fake_fetch):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                qualified, stats = sync.qualify_staging_rows([a1, a2], 50.0)
                self.assertEqual(len(qualified), 2)
                self.assertEqual(calls['n'], 1)

    def test_normalize_instagram_variants(self):
        self.assertEqual(sync.normalize_instagram_url('https://instagram.com/empresa'),
                         'https://www.instagram.com/empresa/')
        self.assertEqual(sync.normalize_instagram_url('empresa'), 'https://www.instagram.com/empresa/')
        self.assertEqual(sync.normalize_instagram_url('@empresa'), 'https://www.instagram.com/empresa/')


class TestSsrfExtended(unittest.TestCase):
    def test_all_private_blocked(self):
        for url in ['http://localhost/', 'http://127.0.0.1/', 'http://10.0.0.1/',
                    'http://172.16.0.1/', 'http://192.168.0.1/',
                    'http://169.254.169.254/', 'http://[::1]/']:
            res = sync.fetch_public_html_result(url)
            self.assertEqual(res.status, 'UNSAFE_URL', url)

    def test_redirect_public_to_private_blocked(self):
        def fake_safe(url):
            if 'evil' in url:
                return False
            return True

        with patch.object(sync, 'is_safe_public_url', side_effect=fake_safe):
            with patch('urllib.request.OpenerDirector.open') as mock_open:
                err = __import__('urllib.error', fromlist=['HTTPError']).HTTPError(
                    'http://example.com/', 302, 'Found', {'Location': 'http://evil-private/'}, None)
                mock_open.side_effect = err
                res = sync.fetch_public_html_result('http://example.com/')
                self.assertEqual(res.status, 'UNSAFE_URL')

    def test_hub_redirect_private_blocked(self):
        with patch.object(sync, 'is_safe_public_url', side_effect=lambda u: 'linktr.ee' in u):
            with patch('urllib.request.OpenerDirector.open') as mock_open:
                err = __import__('urllib.error', fromlist=['HTTPError']).HTTPError(
                    'https://linktr.ee/x', 302, 'Found', {'Location': 'http://10.0.0.1/'}, None)
                mock_open.side_effect = err
                res = sync.fetch_contact_hub_result('https://linktr.ee/x', 'br', cache={})
                self.assertEqual(res.status, 'UNSAFE_URL')

    def test_social_redirect_private_blocked(self):
        with patch.object(sync, 'is_safe_public_url', side_effect=lambda u: 'instagram.com' in u):
            with patch('urllib.request.OpenerDirector.open') as mock_open:
                err = __import__('urllib.error', fromlist=['HTTPError']).HTTPError(
                    'https://www.instagram.com/x/', 302, 'Found', {'Location': 'http://192.168.0.1/'}, None)
                mock_open.side_effect = err
                res = sync.fetch_public_social_contact('https://www.instagram.com/x/', 'br')
                self.assertIn(res.status, ('UNSAFE_URL', 'SOCIAL_BLOCKED', 'FETCH_FAILED'))


class TestBrandOperator(unittest.TestCase):
    def test_brand_website_does_not_qualify(self):
        a = make_row(osm_id=61, name='Loja Marca', phone=None, website=None,
                     tags={'shop': 'barber', 'brand:website': 'https://marca.com'})
        with patch.object(sync, 'fetch_public_html_result') as mock_fetch:
            qualified, stats = sync.qualify_staging_rows([a], 50.0)
            mock_fetch.assert_not_called()
            self.assertEqual(len(qualified), 0)
            self.assertEqual(stats['brand_website_seen'], 1)
            self.assertEqual(stats['discarded_no_phone'], 1)

    def test_operator_website_does_not_qualify(self):
        a = make_row(osm_id=62, name='Loja Operador', phone=None, website=None,
                     tags={'shop': 'barber', 'operator:website': 'https://operadora.com'})
        with patch.object(sync, 'fetch_public_html_result') as mock_fetch:
            qualified, stats = sync.qualify_staging_rows([a], 50.0)
            mock_fetch.assert_not_called()
            self.assertEqual(len(qualified), 0)
            self.assertEqual(stats['operator_website_seen'], 1)


class TestCoverageAudit(unittest.TestCase):
    def test_audit_counts(self):
        rows = [
            make_row(osm_id=71, name='A', phone='+5565999991111',
                     tags={'shop': 'barber', 'phone': '+5565999991111'}),
            make_row(osm_id=72, name='B', phone=None, website='https://b.com',
                     tags={'shop': 'barber', 'contact:phone': '+5565999992222',
                           'website': 'https://b.com', 'contact:sms': '65999992222',
                           'instagram': 'lojab', 'facebook': 'lojab',
                           'brand:website': 'https://marca.com'}),
        ]
        cov = sync.audit_contact_coverage(rows)
        self.assertEqual(cov['raw_rows'], 2)
        self.assertEqual(cov['tag_phone'], 1)
        self.assertEqual(cov['tag_contact_phone'], 1)
        self.assertEqual(cov['tag_contact_sms'], 1)
        self.assertEqual(cov['tag_website'], 1)
        self.assertEqual(cov['tag_instagram'], 1)
        self.assertEqual(cov['tag_facebook'], 1)
        self.assertEqual(cov['tag_brand_website'], 1)
        self.assertEqual(cov['with_any_direct_phone_tag'], 2)
        self.assertEqual(cov['with_primary_website'], 1)
        self.assertEqual(cov['with_social_channel'], 1)
        self.assertEqual(cov['with_any_official_channel'], 2)
        self.assertEqual(cov['without_any_official_channel'], 0)


class TestFetchFailureClassification(unittest.TestCase):
    def test_dns_failed(self):
        with patch('socket.getaddrinfo', side_effect=socket.gaierror('dns fail')):
            with patch.object(sync, 'is_safe_public_url', return_value=True):
                res = sync.fetch_public_html_result('https://nonexistent.invalid/')
                self.assertIn(res.status, ('DNS_FAILED', 'FETCH_FAILED'))

    def test_403_404_429_5xx(self):
        import urllib.error
        for code, expected in [(403, 'HTTP_403'), (404, 'HTTP_404'), (429, 'HTTP_429'), (503, 'HTTP_5XX')]:
            with patch('urllib.request.OpenerDirector.open',
                       side_effect=urllib.error.HTTPError('https://example.com/', code, 'err', {}, None)):
                res = sync.fetch_public_html_result('https://example.com/')
                self.assertEqual(res.status, expected, f'code={code}')


if __name__ == '__main__':
    unittest.main()
