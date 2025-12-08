package cellular;

import io.jenetics.Gene;
import io.jenetics.Phenotype;
import io.jenetics.util.ISeq;

public record FilterResult<G extends Gene<?, G>, C extends Comparable<? super C>>(ISeq<Phenotype<G, C>> population,
                                                                                  int invalidCount,
                                                                                  int killCount) {}
