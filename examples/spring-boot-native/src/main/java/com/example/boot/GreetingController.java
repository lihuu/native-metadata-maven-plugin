package com.example.boot;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST surface: a Jackson DTO record in and out, plus the dynamic operation. */
@RestController
@RequestMapping("/api")
public class GreetingController {

    public record GreetingRequest(String name) {
    }

    public record GreetingResponse(String message, int length, List<String> tags) {
    }

    @GetMapping("/greeting")
    public GreetingResponse greeting(@RequestParam(defaultValue = "world") String name) {
        return respond(name);
    }

    @PostMapping("/greeting")
    public GreetingResponse greeting(@RequestBody GreetingRequest request) {
        return respond(request == null || request.name() == null ? "world" : request.name());
    }

    @GetMapping("/dynamic")
    public GreetingResponse dynamic(@RequestParam String method,
                                    @RequestParam(defaultValue = "suffix") String argument) throws Exception {
        String target = System.getProperty("sample.dynamicTarget", "com.example.boot.DynamicTarget");
        String result = DynamicOperation.run(target, method, argument);
        return new GreetingResponse(result, result.length(), List.of("dynamic", method));
    }

    @GetMapping("/health")
    public String health() {
        return "UP";
    }

    private GreetingResponse respond(String name) {
        String message = "Hello, " + name + "!";
        return new GreetingResponse(message, message.length(), List.of("baseline", "jackson"));
    }
}
