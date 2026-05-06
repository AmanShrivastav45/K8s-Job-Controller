package com.k8sgovernor.model;

import java.time.OffsetDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {
    private String id;
    private String name;
    private String status;
    private OffsetDateTime created;
    private OffsetDateTime completed;
}
