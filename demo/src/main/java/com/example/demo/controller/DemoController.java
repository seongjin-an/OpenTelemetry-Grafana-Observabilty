package com.example.demo.controller;

import com.example.demo.service.Demo;
import com.example.demo.service.DemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class DemoController {

    private final DemoService demoService;

    @GetMapping("/api/test")
    public String test() {
        return "demo world";
    }

    @GetMapping("/api/demo")
    public DemoResponse demo(@RequestParam String name) {
        Demo demo = demoService.getDemo(name);
        return DemoResponse.of(demo);
    }
}
