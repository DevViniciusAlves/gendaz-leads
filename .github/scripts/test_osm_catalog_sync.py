#!/usr/bin/env python3
"""
Unit tests for osm_catalog_sync.py
"""

import unittest
import json
import tempfile
import os
from unittest.mock import Mock, patch, MagicMock

# Import the functions we want to test
import sys
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
    def test_valid_node(self):
        feature = {
            'properties': {
                '@type': 'node',
                '@id': 12345,
                'tags': {
                    'name': 'Barbearia Teste',
                    'shop': 'barber',
                    'phone': '+5565999999999'
                }
            },
            'geometry': {'type': 'Point', 'coordinates': [-56.1, -15.6]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertEqual(result['osm_type'], 'node')
        self.assertEqual(result['osm_id'], 12345)
        self.assertEqual(result['business_name'], 'Barbearia Teste')
        self.assertEqual(result['phone'], '+5565999999999')

    def test_valid_way(self):
        feature = {
            'properties': {
                '@type': 'way',
                '@id': 67890,
                'tags': {
                    'name': 'Restaurante Teste',
                    'amenity': 'restaurant'
                }
            },
            'geometry': {'type': 'Polygon', 'coordinates': [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}
        }
        result = parse_feature(feature, 1, 1, 'Cuiabá', 'Mato Grosso', 'br')
        self.assertIsNotNone(result)
        self.assertEqual(result['osm_type'], 'way')

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


if __name__ == '__main__':
    unittest.main()