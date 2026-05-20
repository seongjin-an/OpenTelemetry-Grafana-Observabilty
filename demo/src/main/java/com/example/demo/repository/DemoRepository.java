package com.example.demo.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class DemoRepository {

    private static final Map<String, DemoEntity> DEMO_MAP = new ConcurrentHashMap<>();

    public DemoRepository() {
        initDemoMap();
    }

    private void initDemoMap() {
        DEMO_MAP.put("admin", DemoEntity.of("admin"));
    }

    public Optional<DemoEntity> findByName(String name) {
        return Optional.ofNullable(DEMO_MAP.get(name));
    }
}
