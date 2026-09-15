package com.np.pricehunt.backend.dto;

/** Admin request to invite an email address (issue #249). */
public record CreateInvitationRequest(String email) {}
