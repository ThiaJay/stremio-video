package org.thiajay.stremiotvcompanion;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean failCommit;
    private boolean throwReads;
    private boolean throwEdit;
    private boolean throwCommit;
    private String mutateAfterCommitKey;

    void seed(String key, Object value) { values.put(key, value); }
    Object value(String key) { return values.get(key); }
    Map<String, Object> snapshot() { return new LinkedHashMap<>(values); }
    void setFailCommit(boolean value) { failCommit = value; }
    void setThrowReads(boolean value) { throwReads = value; }
    void setThrowEdit(boolean value) { throwEdit = value; }
    void setThrowCommit(boolean value) { throwCommit = value; }
    void setMutateAfterCommitKey(String key) { mutateAfterCommitKey = key; }

    private void readGate() {
        if (throwReads) throw new IllegalStateException("read failure");
    }

    @Override public Map<String, ?> getAll() {
        readGate();
        return new LinkedHashMap<>(values);
    }

    @Override public String getString(String key, String defValue) {
        readGate();
        Object value = values.get(key);
        if (value == null) return defValue;
        if (!(value instanceof String)) throw new ClassCastException("not a string");
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        readGate();
        Object value = values.get(key);
        if (value == null) return defValues;
        if (!(value instanceof Set<?>)) throw new ClassCastException("not a string set");
        return new HashSet<>((Set<String>) value);
    }

    @Override public int getInt(String key, int defValue) {
        readGate();
        Object value = values.get(key);
        return value == null ? defValue : (Integer) value;
    }

    @Override public long getLong(String key, long defValue) {
        readGate();
        Object value = values.get(key);
        return value == null ? defValue : (Long) value;
    }

    @Override public float getFloat(String key, float defValue) {
        readGate();
        Object value = values.get(key);
        return value == null ? defValue : (Float) value;
    }

    @Override public boolean getBoolean(String key, boolean defValue) {
        readGate();
        Object value = values.get(key);
        return value == null ? defValue : (Boolean) value;
    }

    @Override public boolean contains(String key) {
        readGate();
        return values.containsKey(key);
    }

    @Override public Editor edit() {
        if (throwEdit) throw new IllegalStateException("edit failure");
        return new EditorImpl();
    }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private final class EditorImpl implements Editor {
        private final Map<String, Object> puts = new HashMap<>();
        private final List<String> removes = new ArrayList<>();
        private boolean clear;

        @Override public Editor putString(String key, String value) { puts.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) {
            puts.put(key, values == null ? null : new HashSet<>(values));
            return this;
        }
        @Override public Editor putInt(String key, int value) { puts.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { puts.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { puts.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { puts.put(key, value); return this; }
        @Override public Editor remove(String key) { removes.add(key); return this; }
        @Override public Editor clear() { clear = true; return this; }

        @Override public boolean commit() {
            if (throwCommit) throw new IllegalStateException("commit failure");
            if (failCommit) return false;
            if (clear) values.clear();
            for (String key : removes) values.remove(key);
            for (Map.Entry<String, Object> entry : puts.entrySet()) {
                if (entry.getValue() == null) values.remove(entry.getKey());
                else values.put(entry.getKey(), entry.getValue());
            }
            if (mutateAfterCommitKey != null) values.put(mutateAfterCommitKey, "__drift__");
            return true;
        }

        @Override public void apply() { commit(); }
    }
}
