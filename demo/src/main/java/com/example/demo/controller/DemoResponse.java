package com.example.demo.controller;

import com.example.demo.service.Demo;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Data
public class DemoResponse {
    private String name;

    public static DemoResponse of(Demo demo) {
        return new DemoResponse(demo.getName());
    }
}
