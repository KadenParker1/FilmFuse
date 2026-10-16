package com.pm.filmfuse.reco.ml;

import java.util.List;

/** ML contract {@code UserVector}: interpretable genre+mood dims (1-10) plus an optional learned latent. */
public record MlUserVector(List<Double> interpretable, List<Double> latent) {
}
