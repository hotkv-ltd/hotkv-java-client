// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** A longitude/latitude pair, as used by {@code GEOADD}/{@code GEOPOS}. */
public record GeoPoint(double longitude, double latitude) {
}
