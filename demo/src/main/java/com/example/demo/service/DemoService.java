package com.example.demo.service;

import com.example.demo.repository.DemoEntity;
import com.example.demo.repository.DemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class DemoService {

    private final DemoRepository demoRepository;

    public Demo getDemo(String name) {
        log.info("got demo request for {}", name);
        DemoEntity demoEntity = demoRepository.findByName(name).orElseThrow();
        return Demo.of(demoEntity);
    }
}
