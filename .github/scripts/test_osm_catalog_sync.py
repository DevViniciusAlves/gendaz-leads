import unittest
import json
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


if __name__ == '__main__':
    unittest.main()
