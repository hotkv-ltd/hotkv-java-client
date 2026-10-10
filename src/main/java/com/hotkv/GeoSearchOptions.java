// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import java.util.ArrayList;
import java.util.List;

/**
 * Search origin, shape and output options for {@code GEOSEARCH}. Exactly
 * one origin ({@link #fromMember} or {@link #fromLonLat}) and one shape
 * ({@link #byRadius} or {@link #byBox}) must be set.
 */
public final class GeoSearchOptions {

    private String fromMember;
    private Double fromLon;
    private Double fromLat;
    private Double radius;
    private String radiusUnit;
    private Double boxWidth;
    private Double boxHeight;
    private String boxUnit;
    private boolean asc;
    private boolean desc;
    private Long count;
    private boolean any;
    private boolean withCoord;
    private boolean withDist;
    private boolean withHash;

    private GeoSearchOptions() {
    }

    public static GeoSearchOptions builder() {
        return new GeoSearchOptions();
    }

    public GeoSearchOptions fromMember(String member) {
        this.fromMember = member;
        return this;
    }

    public GeoSearchOptions fromLonLat(double longitude, double latitude) {
        this.fromLon = longitude;
        this.fromLat = latitude;
        return this;
    }

    /** Unit is one of "m", "km", "mi", "ft". */
    public GeoSearchOptions byRadius(double radius, String unit) {
        this.radius = radius;
        this.radiusUnit = unit;
        return this;
    }

    public GeoSearchOptions byBox(double width, double height, String unit) {
        this.boxWidth = width;
        this.boxHeight = height;
        this.boxUnit = unit;
        return this;
    }

    public GeoSearchOptions asc() {
        this.asc = true;
        return this;
    }

    public GeoSearchOptions desc() {
        this.desc = true;
        return this;
    }

    public GeoSearchOptions count(long count, boolean any) {
        this.count = count;
        this.any = any;
        return this;
    }

    public GeoSearchOptions withCoord() {
        this.withCoord = true;
        return this;
    }

    public GeoSearchOptions withDist() {
        this.withDist = true;
        return this;
    }

    public GeoSearchOptions withHash() {
        this.withHash = true;
        return this;
    }

    boolean wantsCoord() {
        return withCoord;
    }

    boolean wantsDist() {
        return withDist;
    }

    boolean wantsHash() {
        return withHash;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(16);
        if (fromMember != null) {
            args.add("FROMMEMBER");
            args.add(fromMember);
        } else if (fromLon != null) {
            args.add("FROMLONLAT");
            args.add(Numbers.decimal(fromLon));
            args.add(Numbers.decimal(fromLat));
        }
        if (radius != null) {
            args.add("BYRADIUS");
            args.add(Numbers.decimal(radius));
            args.add(radiusUnit);
        } else if (boxWidth != null) {
            args.add("BYBOX");
            args.add(Numbers.decimal(boxWidth));
            args.add(Numbers.decimal(boxHeight));
            args.add(boxUnit);
        }
        if (asc) {
            args.add("ASC");
        }
        if (desc) {
            args.add("DESC");
        }
        if (count != null) {
            args.add("COUNT");
            args.add(count);
            if (any) {
                args.add("ANY");
            }
        }
        if (withCoord) {
            args.add("WITHCOORD");
        }
        if (withDist) {
            args.add("WITHDIST");
        }
        if (withHash) {
            args.add("WITHHASH");
        }
        return args;
    }
}
