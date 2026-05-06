package com.k8sgovernor.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateJobRequest {
    private String templateName;
    private Map<String, Object> overrides;
}
