package com.example.demo.repository;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Data
public class DemoEntity {
    private String name;

    public static DemoEntity of(String name) {
        return new DemoEntity(name);
    }
}
