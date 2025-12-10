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

/**
 * Parallel fitness evaluator for Jenetics phenotypes.
 *
 * <p>
 * This class adapts a user-provided fitness function so that it can evaluate
 * the fitness of each individual in the population using an
 * {@link ExecutorService}. Each phenotype is evaluated in its own submitted
 * task, allowing execution on multiple threads when an appropriate executor
 * (e.g., fixed thread pool) is supplied.
 * </p>
 *
 * <p>
 * The evaluator checks whether each phenotype already stores a valid fitness
 * value (via {@link Phenotype#isEvaluated()}). If so, it is returned unchanged.
 * Otherwise, the fitness function is applied to the phenotype's genotype and a
 * new evaluated phenotype is produced.
 * </p>
 *
 * @param <G> the Jenetics gene type
 * @param <C> the fitness result type, must be comparable
 */
public class FitnessEvaluator<G extends Gene<?, G>, C extends Comparable<? super C>> implements Evaluator<G, C> {

  /**
   * User-defined fitness function mapping {@link Genotype} to a fitness value.
   * This function is applied only if the phenotype is not already evaluated.
   */
  private final Function<? super Genotype<G>, ? extends C> fitness;

  /**
   * Executor used to run fitness evaluations concurrently. The caller is
   * responsible for lifecycle management (shutdown).
   */
  private final ExecutorService pool;

  /**
   * Constructs a new parallel fitness evaluator.
   *
   * @param fitness the fitness function to apply to genotypes
   * @param pool    the executor service used for parallel evaluation
   */
  public FitnessEvaluator(Function<? super Genotype<G>, ? extends C> fitness, ExecutorService pool) {
    this.fitness = fitness;
    this.pool = pool;
  }

  /**
   * Evaluates the given population in parallel.
   *
   * <p>
   * For each phenotype in the input sequence:
   * <ul>
   * <li>If {@code ph.isEvaluated() == true}, returns the phenotype
   * unchanged.</li>
   * <li>Otherwise computes the fitness using the provided function and returns a
   * new {@link Phenotype} containing the fitness value.</li>
   * </ul>
   * </p>
   *
   * <p>
   * All individuals are submitted to the executor and evaluation is synchronized
   * at the end by waiting on all {@link Future} objects.
   * </p>
   *
   * @param population the current generation's population
   * @return an immutable sequence containing evaluated phenotypes
   * @throws RuntimeException if any evaluation task throws or the thread is
   *                          interrupted
   */
  @Override
  public ISeq<Phenotype<G, C>> eval(final Seq<Phenotype<G, C>> population) {

    try {
      final List<Future<Phenotype<G, C>>> futures = new ArrayList<>(population.size());

      // Submit tasks for parallel evaluation
      for (Phenotype<G, C> ph : population) {
        futures.add(pool.submit(() -> {
          if (ph.isEvaluated()) {
            // Already has a fitness value; no need to recompute
            return ph;
          } else {
            // Compute fitness and construct a new evaluated phenotype
            C fit = fitness.apply(ph.genotype());
            return Phenotype.of(ph.genotype(), ph.generation(), fit);
          }
        }));
      }

      // Gather evaluated individuals from futures
      List<Phenotype<G, C>> evaluatedPopulation = new ArrayList<>(population.size());
      for (Future<Phenotype<G, C>> future : futures) {
        evaluatedPopulation.add(future.get());
      }

      return ISeq.of(evaluatedPopulation);

    } catch (InterruptedException e) {
      // Re-establish interrupt status and propagate
      Thread.currentThread()
            .interrupt();
      throw new RuntimeException("Fitness evaluation interrupted", e);

    } catch (ExecutionException e) {
      throw new RuntimeException("Exception during fitness evaluation", e);
    }
  }
}
