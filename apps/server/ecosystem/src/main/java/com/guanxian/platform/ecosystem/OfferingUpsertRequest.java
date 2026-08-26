package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OfferingUpsertRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Pattern(regexp = "PRODUCT|SERVICE") String kind,
        @Size(max = 5000) String description,
        @Size(max = 30) List<@NotBlank @Size(max = 100) String> scenarios,
        @Size(max = 30) List<@NotBlank @Size(max = 200) String> qualifications,
        @Pattern(regexp = "PRIVATE|MEMBERS|PARTNERS|PUBLIC") String visibility) {
}
