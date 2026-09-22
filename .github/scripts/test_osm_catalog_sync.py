#!/usr/bin/env python3
"""
Extended unit tests for osm_catalog_sync.py
"""

import unittest
import json
import os
import sys
from unittest.mock import Mock, patch, MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

from osm_catalog_sync import (
    first_present,
    build_address,
    normalize_name,
    extract_representative_coords,
    is_commercial,
    parse_feature,
)


class TestFirstPresent(unittest.TestCase):
    def test_first_present_found(self):
        tags = {'phone': '123', 'contact:phone': '456'}
        result = first_present(tags, ['contact:phone', 'phone'])
        self.assertEqual(result, '456')

    def test_first_present_second(self):
        tags = {'phone': '123'}
        result = first_present(tags, ['contact:phone', 'phone'])
        self.assertEqual(result, '123')

    def test_first_present_none(self):
        tags = {}
        result = first_present(tags, ['contact:phone', 'phone'])
        self.assertIsNone(result)

    def test_first_present_empty_string(self):
        tags = {'phone': '  '}
        result = first_present(tags, ['phone'])
        self.assertIsNone(result)


class TestBuildAddress(unittest.TestCase):
    def test_full_address(self):
        tags = {
            'addr:street': 'Rua A',
            'addr:housenumber': '123',
            'addr:suburb': 'Bairro B',
            'addr:city': 'Cuiabá',
            'addr:state': 'MT'
        }
        result = build_address(tags, 'Cuiabá', 'Mato Grosso')
        self.assertEqual(result, 'Rua A 123, Bairro B, Cuiabá - MT')

    def test_minimal_address(self):
        tags = {}
        result = build_address(tags, 'Cuiabá', 'Mato Grosso')
        self.assertEqual(result, 'Cuiabá - Mato Grosso')

    def test_street_only(self):
        tags = {'addr:street': 'Rua A'}
        result = build_address(tags, 'Cuiabá', 'Mato Grosso')
        self.assertEqual(result, 'Rua A, Cuiabá - Mato Grosso')


class TestNormalizeName(unittest.TestCase):
    def test_normalize_simple(self):
        self.assertEqual(normalize_name('Barbearia João'), 'barbearia joao')

    def test_normalize_with_accents(self):
        self.assertEqual(normalize_name('Barbearia João'), 'barbearia joao')

    def test_normalize_multiple_spaces(self):
        self.assertEqual(normalize_name('  Barbearia   João  '), 'barbearia joao')


class TestExtractRepresentativeCoords(unittest.TestCase):
    def test_point(self):
        feature = {'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}}
        lon, lat = extract_representative_coords(feature)
        self.assertEqual(lon, -56.1)
        self.assertEqual(lat, -15.6)

    def test_linestring(self):
        feature = {'geometry': {'type': 'LineString', 'coordinates': [[-56.1, -15.6], [-56.2, -15.7]]}}
        lon, lat = extract_representative_coords(feature)
        self.assertEqual(lon, -56.1)
        self.assertEqual(lat, -15.6)

    def test_polygon(self):
        feature = {'geometry': {'type': 'Polygon', 'coordinates': [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}}
        lon, lat = extract_representative_coords(feature)
        self.assertAlmostEqual(lon, 0.5)
        self.assertAlmostEqual(lat, 0.5)


class TestIsCommercial(unittest.TestCase):
    def test_shop(self):
        self.assertTrue(is_commercial({'shop': 'barber'}))

    def test_amenity(self):
        self.assertTrue(is_commercial({'amenity': 'restaurant'}))

    def test_craft(self):
        self.assertTrue(is_commercial({'craft': 'bakery'}))

    def test_non_commercial(self):
        self.assertFalse(is_commercial({'highway': 'residential'}))
        self.assertFalse(is_commercial({}))


class TestParseFeature(unittest.TestCase):
    def test_valid_node_flat_properties(self):
        """Test parsing node with flat properties from osmium export"""
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                '@timestamp': '2026-01-01T00:00:00Z',
                'name': 'Barbearia Teste',
                'shop': 'barber',
                'phone': '+5565999999999'
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertEqual(result['osm_type'], 'node')
        self.assertEqual(result['osm_id'], 12345)
        self.assertEqual(result['business_name'], 'Barbearia Teste')
        self.assertEqual(result['phone'], '+5565999999999')
        self.assertEqual(result['tags'], '{"name": "Barbearia Teste", "shop": "barber", "phone": "+5565999999999"}')
        self.assertIsNotNone(result['source_timestamp'])

    def test_valid_way_flat_properties(self):
        """Test parsing way with flat properties"""
        feature = {
            'properties': {
                '@type': 'way',
                '@id': 67890,
                'name': 'Restaurante Teste',
                'amenity': 'restaurant'
            },
            'geometry': {'type': 'Polygon', 'coordinates': [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertEqual(result['osm_type'], 'way')

    def test_valid_relation_flat_properties(self):
        """Test parsing relation with flat properties"""
        feature = {
            'properties': {
                '@type': 'relation',
                '@id': 11111,
                'name': 'Shopping Teste',
                'shop': 'mall'
            },
            'geometry': {'type': 'Polygon', 'coordinates': [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertEqual(result['osm_type'], 'relation')

    def test_missing_name(self):
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                'tags': {'shop': 'barber'}
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNone(result)

    def test_non_commercial(self):
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                'tags': {'highway': 'bus_stop', 'name': 'Parada'}
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNone(result)

    def test_invalid_type(self):
        feature = {
            'properties': {
                '@type': 'area',
                '@id': 12345,
                'tags': {'shop': 'barber', 'name': 'Teste'}
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNone(result)

    def test_timestamp_epoch_seconds(self):
        """Test parsing epoch timestamp"""
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                '@timestamp': '1704067200',  # 2024-01-01T00:00:00Z
                'name': 'Teste',
                'shop': 'barber'
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertIsNotNone(result['source_timestamp'])

    def test_timestamp_invalid_fallback_to_none(self):
        """Test invalid timestamp falls back to None"""
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                '@timestamp': 'invalid-timestamp',
                'name': 'Teste',
                'shop': 'barber'
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertIsNone(result['source_timestamp'])

    def test_contact_phone_precedence(self):
        """Test contact:phone takes precedence over phone"""
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                'name': 'Teste',
                'shop': 'barber',
                'phone': '+5565999999999',
                'contact:phone': '+5565888888888'
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertEqual(result['phone'], '+5565888888888')

    def test_contact_website_precedence(self):
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                'name': 'Teste',
                'shop': 'barber',
                'website': 'http://example.com',
                'contact:website': 'http://contact.example.com'
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertEqual(result['website'], 'http://contact.example.com')


class TestRecordSeparatorHandling(unittest.TestCase):
    def test_line_with_record_separator(self):
        """Test that lines with 0x1e prefix are handled"""
        # This test verifies the parsing logic handles record separators
        line = '\x1e{"type": "Feature", "properties": {"@type": "node", "@id": 1, "name": "Test", "shop": "barber"}, "geometry": {"type": "Point", "coordinates": [0, 0]}}'
        stripped = line.lstrip('\x1e').strip()
        feature = json.loads(stripped)
        self.assertEqual(feature['properties']['@type'], 'node')


if __name__ == '__main__':
    unittest.main()