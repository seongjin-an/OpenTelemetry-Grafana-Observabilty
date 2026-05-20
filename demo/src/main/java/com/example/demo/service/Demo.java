package com.example.demo.service;

import com.example.demo.repository.DemoEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Data
public class Demo {
    private String name;

    public static Demo of(DemoEntity entity) {
        return new Demo(entity.getName());
    }
}
