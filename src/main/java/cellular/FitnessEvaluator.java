package cellular;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

import io.jenetics.Gene;
import io.jenetics.Genotype;
import io.jenetics.Phenotype;
import io.jenetics.engine.Evaluator;
import io.jenetics.util.ISeq;
import io.jenetics.util.Seq;

public class FitnessEvaluator<G extends Gene<?, G>, C extends Comparable<? super C>> implements Evaluator<G, C> {

  private final Function<? super Genotype<G>, ? extends C> fitness;
  private final ExecutorService pool;

  public FitnessEvaluator(Function<? super Genotype<G>, ? extends C> fitness, ExecutorService pool) {
    super();
    this.fitness = fitness;
    this.pool = pool;
  }

  @Override
  public ISeq<Phenotype<G, C>> eval(Seq<Phenotype<G, C>> population) {

    try {
      List<Future<Phenotype<G, C>>> futures = new ArrayList<>();
      for (Phenotype<G, C> ph : population) {
        futures.add(pool.submit(() -> {
          if (ph.isEvaluated()) {
            return ph;
          } else {
            return Phenotype.of(ph.genotype(), ph.generation(), fitness.apply(ph.genotype()));
          }
        }));
      }
      List<Phenotype<G, C>> evaluatedPopulation = new ArrayList<>(population.size());
      for (Future<Phenotype<G, C>> future : futures) {
        evaluatedPopulation.add(future.get());
      }
      return ISeq.of(evaluatedPopulation);
    } catch (InterruptedException e) {
      Thread.currentThread()
            .interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}
