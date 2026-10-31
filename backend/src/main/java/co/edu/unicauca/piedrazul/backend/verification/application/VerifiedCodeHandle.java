package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;

import java.util.UUID;

record VerifiedCodeHandle(UUID codeId) implements VerifiedCode {
}
