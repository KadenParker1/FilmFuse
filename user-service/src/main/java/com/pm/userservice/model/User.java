package com.pm.userservice.model;


import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Entity
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO) // Generate a 128 bit unique identifing number for a user
    private UUID id;

    @NotNull
    private String name;

    @NotNull
    @Email
    @Column(unique = true)
    private String email;

    @NotNull
    private String preference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY;

    @NotNull
    private LocalDate registerDate;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPreference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY() {
        return preference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY;
    }

    public void setPreference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY(String preference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY) {
        this.preference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY = preference_vector_UPDATETYPETOBENEWJAVACLASSORFINDLIBRARY;
    }

    public LocalDate getRegisterDate() {
        return registerDate;
    }

    public void setRegisterDate(LocalDate registerDate) {
        this.registerDate = registerDate;
    }
}
