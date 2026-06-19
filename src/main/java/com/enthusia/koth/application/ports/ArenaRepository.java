package com.enthusia.koth.application.ports;

import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.KothFamily;

import java.util.Optional;

public interface ArenaRepository {
    Optional<ArenaDefinition> findDefault(KothFamily family);
}
