package com.ecommerce.customer.controller;

import com.ecommerce.customer.dto.*;
import com.ecommerce.customer.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse register(@Valid @RequestBody RegisterRequest request) {
        return customerService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return customerService.login(request);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable Long id) {
        return customerService.findById(id);
    }

    /** Convenience endpoint used after the gateway resolves the caller's identity from the JWT. */
    @GetMapping("/me")
    public CustomerResponse me(@RequestHeader("X-User-Id") Long userId) {
        return customerService.findById(userId);
    }
}
