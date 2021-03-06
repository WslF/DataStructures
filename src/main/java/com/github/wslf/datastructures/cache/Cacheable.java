package com.github.wslf.datastructures.cache;

import com.github.wslf.datastructures.set.TreeSetExtended;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stores data at the cache and updates most often used.
 *
 * @author Wsl_F
 * @param <CachedT> type of cached data
 * @param <KeyT> type of key
 */
public abstract class Cacheable<CachedT, KeyT> {

    /**
     * Maximum number of cached items.
     */
    private final int MAX_CACHED_DATA;

    /**
     * Maximum number of most used cached items for which value is updated in
     * background.
     */
    private final int MAX_UPDATED_DATA;

    /**
     * Time (in milliseconds) after that value of top {@code MAX_UPDATED_DATE}
     * used items is updated.
     */
    private final long TIME_TO_UPDATE_MS;

    /**
     * Constructor.
     *
     * @param MAX_CACHED_DATA Maximum number of cached items.
     * @param MAX_UPDATED_DATA Maximum number of most used cached items for
     * which value is updated in background.
     * @param TIME_TO_UPDATE_MS Time after that value of top
     * {@code MAX_UPDATED_DATE} used items is updated.
     */
    public Cacheable(int MAX_CACHED_DATA, int MAX_UPDATED_DATA, long TIME_TO_UPDATE_MS) {
        this.updating = new HashSet<>();
        this.MAX_CACHED_DATA = MAX_CACHED_DATA;
        this.MAX_UPDATED_DATA = MAX_UPDATED_DATA;
        this.TIME_TO_UPDATE_MS = TIME_TO_UPDATE_MS;
    }

    private volatile TreeSetExtended<CachedItem<CachedT, KeyT>> cacheSet = new TreeSetExtended<>();

    private volatile ConcurrentMap<KeyT, CachedItem<CachedT, KeyT>> cacheMap = new ConcurrentHashMap<>();

    /**
     * getting value by long time calculation or so on
     *
     * @param key key
     * @return calculated value
     */
    public abstract CachedT getValueManually(KeyT key);

    private CachedT getValueCached(KeyT key) {
        CachedItem<CachedT, KeyT> cachedItem = cacheMap.get(key);
        cachedItem.use();
        if (cachedItem.timeSinceCreated(System.currentTimeMillis())
                > TIME_TO_UPDATE_MS) {
            update(cachedItem);
        }

        return cachedItem.getValue();
    }

    /**
     * Get value by key. If cache contains cached value, it be returned.
     * Otherwise value be calculated manually.
     *
     * @param key specific key
     * @return value
     */
    public CachedT getValue(KeyT key) {
        CachedT result;
        if (contains(key)) {
            result = getValueCached(key);
        } else {
            result = getValueManually(key);
            addToCacheIfNeed(key, result);
        }

        updateCache();
        return result;
    }

    private void decreaseUsing() {
        cacheSet.forEach((item) -> {
            item.decrease();
        });
    }

    /**
     * Check does cache contains specific key
     *
     * @param key key for check
     * @return if cache contains key return true, otherwise - false
     */
    public boolean contains(KeyT key) {
        return cacheMap.containsKey(key);
    }

    private void addToCacheIfNeed(KeyT key, CachedT value) {
        if (!contains(key)) {
            boolean add;
            CachedItem<CachedT, KeyT> newItem = new CachedItem<>(key, value);
            add = (cacheMap.size() < MAX_CACHED_DATA)
                    || (cacheSet.size() == MAX_CACHED_DATA
                    && newItem.compareTo(cacheSet.last()) < 0);

            if (add) {
                addToCache(newItem);
                removeExcess();
            }
        }
    }

    /**
     * adding item to cache without any check
     *
     * @param newItem item to be added to the cache
     */
    private void addToCache(CachedItem<CachedT, KeyT> newItem) {
        cacheMap.put(newItem.getKey(), newItem);
        cacheSet.add(newItem);
    }

    private void removeExcess() {
        while (cacheSet.size() > MAX_CACHED_DATA) {
            CachedItem<CachedT, KeyT> excessItem = cacheSet.last();
            remove(excessItem.getKey());
        }
    }

    /**
     * remove item with specified key from the cache
     *
     * @param key key of item to be removed
     */
    public void remove(KeyT key) {
        CachedItem<CachedT, KeyT> item = cacheMap.remove(key);
        if (item != null) {
            cacheSet.remove(item);
        }
    }

    void updateValueInCache(KeyT key) {
        CachedT newValue = getValueManually(key);

        CachedItem<CachedT, KeyT> currentItem = cacheMap.get(key);
        CachedItem<CachedT, KeyT> newItem = new CachedItem<>(key, newValue, currentItem.getAccessTime());

        synchronized (this) {
            remove(key);
            addToCache(newItem);
        }

        updating.remove(key);
    }

    private void updateCache() {
        decreaseUsing();

        long curTime = System.currentTimeMillis();
        for (CachedItem<CachedT, KeyT> item : cacheSet.getFirstK(MAX_UPDATED_DATA)) {
            if (item.needUpdate(curTime, TIME_TO_UPDATE_MS)) {
                update(item);
            }
        }
    }
    /**
     * contains keys of items that updating now in separate thread
     */
    private volatile Set<KeyT> updating;

    /**
     * updating cached value of item in separate thread
     *
     * @param item
     */
    private synchronized void update(CachedItem<CachedT, KeyT> item) {
        if (!updating.contains(item.getKey())) {
            if (item.needUpdate(System.currentTimeMillis(), TIME_TO_UPDATE_MS)) {
                updating.add(item.getKey());
                new Thread(new CalculatingManually<>(this, item.getKey())).start();
            }
        }
    }

    /**
     * Returns set of keys of all cached items.
     *
     * @return set, that contains all cached keys.
     */
    public Set<KeyT> getCachedKeys() {
        return cacheMap.keySet();
    }

    /**
     * Returns time of last update item with specified key to the cache.
     * <br> if cache doesn't contain such item, returns {@code 0}
     *
     * @param key key of item
     * @return time of last adding item with specified {@code key} to the cache.
     */
    public long getUpdateTime(KeyT key) {
        if (!contains(key)) {
            return 0;
        }

        CachedItem<CachedT, KeyT> cachedItem = cacheMap.get(key);
        return cachedItem.getCreationTime();
    }
}
