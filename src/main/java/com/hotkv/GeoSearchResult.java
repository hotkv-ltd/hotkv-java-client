// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/**
 * One match from {@code GEOSEARCH}. Fields not requested with the
 * corresponding {@code WITH...} option are null.
 */
public record GeoSearchResult(String member, Double distance, GeoPoint coordinates, Long geohash) {
}
