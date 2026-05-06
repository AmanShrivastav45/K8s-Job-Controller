package com.k8sgovernor.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobCreateRequest {
    private String templateName;
    private Map<String, Object> overrides;
}
