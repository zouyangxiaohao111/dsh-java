package dev.dsh.cordis.util;

import java.util.*;

/** Ordered disposable collection with O(1) deletion and reverse-order clear. */
public final class DisposableList<T> implements Iterable<T> {
    private long sn = 0;
    private final Map<Long, T> map = new LinkedHashMap<>();
    private final Map<T, Long> index = new IdentityHashMap<>();

    public int length() { return map.size(); }

    /** Append and return a remover that deletes this entry. */
    public Runnable push(T value) {
        long id = ++sn;
        map.put(id, value);
        index.put(value, id);
        return () -> map.remove(id);
    }

    public boolean delete(T value) {
        Long id = index.remove(value);
        if (id == null) return false;
        map.remove(id);
        return true;
    }

    /** Remove everything; returns values in REVERSE insertion order (for reverse cleanup). */
    public List<T> clear() {
        List<T> values = new ArrayList<>(map.values());
        map.clear();
        index.clear();
        Collections.reverse(values);
        return values;
    }

    @Override
    public Iterator<T> iterator() { return map.values().iterator(); }
}
