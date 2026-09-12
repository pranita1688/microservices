package com.ecommerce.customer.service;

import com.ecommerce.customer.dto.*;
import com.ecommerce.customer.exception.DuplicateEmailException;
import com.ecommerce.customer.exception.InvalidCredentialsException;
import com.ecommerce.customer.exception.ResourceNotFoundException;
import com.ecommerce.customer.model.Customer;
import com.ecommerce.customer.repository.CustomerRepository;
import com.ecommerce.customer.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public CustomerResponse register(RegisterRequest request) {
        if (repository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new DuplicateEmailException("Email already registered: " + request.getEmail());
        }
        Customer customer = Customer.builder()
                .name(request.getName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .address(request.getAddress())
                .build();
        return CustomerResponse.from(repository.save(customer));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Customer customer = repository.findByEmailIgnoreCase(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(request.getPassword(), customer.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }
        String token = jwtTokenProvider.generateToken(customer.getId(), customer.getEmail());
        return new AuthResponse(token, customer.getId(), customer.getName(), customer.getEmail());
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(Long id) {
        return CustomerResponse.from(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id)));
    }
}
