package com.ticketing.sale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cache parsing only: every lookup still validates the currently read Redis bytes and DB digest. */
public final class SaleManifestCache {
    private final int capacity;
    private final Map<String, SaleManifest> entries = new LinkedHashMap<>(16, .75f, true);
    public SaleManifestCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public synchronized SaleManifest get(String session, long version, String digest, String payload, ObjectMapper json) {
        BusinessException.require(payload != null, "场次装配快照缺失，需显式恢复");
        String key = session + ":" + version;
        SaleManifest result = entries.get(key);
        if (result == null || !result.json().equals(payload)) result = new SaleManifest(payload, json);
        BusinessException.require(result.version() == version && session.equals(result.sessionId())
                && result.digest().equals(digest), "场次快照摘要或版本不一致");
        entries.put(key, result);
        if (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
        return result;
    }
    synchronized int size() { return entries.size(); }
}
