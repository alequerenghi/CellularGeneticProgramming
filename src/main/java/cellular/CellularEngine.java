package cellular;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.ForkJoinPool.commonPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.jenetics.Alterer;
import io.jenetics.AltererResult;
import io.jenetics.Gene;
import io.jenetics.Genotype;
import io.jenetics.Optimize;
import io.jenetics.Phenotype;
import io.jenetics.Selector;
import io.jenetics.engine.Codec;
import io.jenetics.engine.Constraint;
import io.jenetics.engine.Evaluator;
import io.jenetics.engine.Evolution;
import io.jenetics.engine.EvolutionDurations;
import io.jenetics.engine.EvolutionInit;
import io.jenetics.engine.EvolutionParams;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.EvolutionStart;
import io.jenetics.engine.EvolutionStream;
import io.jenetics.engine.EvolutionStreamable;
import io.jenetics.engine.Problem;
import io.jenetics.engine.RetryConstraint;
import io.jenetics.util.Factory;
import io.jenetics.util.ISeq;
import io.jenetics.util.MSeq;
import io.jenetics.util.Seq;

/**
 * Custom evolutionary engine implementing a Cellular Genetic Algorithm.
 *
 * The engine differs from the default Jenetics Engine in the following
 * ways:<br>
 * - Each individual evolves independently based on its neighborhood
 * connections.<br>
 * - Selection occurs locally within a node's neighbors, not globally.<br>
 * - Replacement is local: an offspring replaces the parent only if it improves
 * fitness.<br>
 * - All local evolution steps can execute in parallel via an
 * ExecutorService.<br>
 * - Arbitrary topologies are supported (grids, random, scale-free, etc.).<br>
 */
public class CellularEngine<G extends Gene<?, G>,
                            C extends Comparable<? super C>>
    implements
    Evolution<G, C>,
    Evaluator<G, C>,
    EvolutionStreamable<G, C> {

  /** Graph defining neighborhood connections between individuals. */
  private final GraphMap connections;

  /** Responsible for evaluating fitness values. */
  private final Evaluator<G, C> evaluator;

  /** Factory for generating new random genotypes. */
  private final Factory<Genotype<G>> genotypeFactory;

  /** Constraint object (validity checking, repairing, max age enforcement). */
  private final Constraint<G, C> constraint;

  /** Whether to maximize or minimize fitness. */
  private final Optimize optimize;

  /** Evolution parameters (alterers, selectors, limits, phenotype age). */
  private final EvolutionParams<G, C> evolutionParams;

  /** Execution pool used to parallelize per-node evolution. */
  private final ExecutorService pool;

  CellularEngine(GraphMap connections,
                 Evaluator<G, C> evaluator,
                 Factory<Genotype<G>> genotypeFactory,
                 Constraint<G, C> constraint,
                 Optimize optimize,
                 EvolutionParams<G, C> evolutionParams,
                 ExecutorService pool) {

    this.evaluator = evaluator;
    this.connections = connections;
    this.genotypeFactory = genotypeFactory;
    this.constraint = constraint;
    this.optimize = optimize;
    this.evolutionParams = evolutionParams;
    this.pool = pool;
  }

  /**
   * Performs one complete generation of the cellular evolutionary process.
   *
   * Execution steps:<br>
   * 1. Filter: repair invalid genotypes, kill too-old phenotypes.<br>
   * 2. Evaluate the entire population.<br>
   * 3. For each node (in parallel):<br>
   * - Extract neighbors.<br>
   * - Perform local selection among neighbors.<br>
   * - Apply alterers (mutation, crossover).<br>
   * - Return the evolved individual.<br>
   * 4. Evaluate offspring.<br>
   * 5. Replace parent only if offspring is fitter.<br>
   */
  @Override
  public EvolutionResult<G, C> evolve(final EvolutionStart<G, C> start) {

    int invalidCount = 0;
    int killCount = 0;
    int alterCount = 0;

    try {
      // Step 1: constraint-based filtering
      final FilterResult<G, C> filtered = filter(start.population(), start.generation());

      // Step 2: evaluate population fitness
      final Seq<Phenotype<G, C>> population = eval(filtered.population());

      // Prepare asynchronous tasks for local evolution
      final List<Future<Phenotype<G, C>>> futures = new ArrayList<>(populationSize());

      for (int i = 0; i < populationSize(); i++) {

        // Build neighbor list for node i
        final List<Phenotype<G, C>> neighbors = new LinkedList<>();
        connections.getConnections(i)
                   .forEach(j -> neighbors.add(population.get(j)));

        // If no neighbors, individual evolves alone
        final Seq<Phenotype<G, C>> parents = neighbors.isEmpty() ? ISeq.of(population.get(i)) : ISeq.of(neighbors);

        // Submit a local evolution task
        futures.add(pool.submit(() -> evolveSingle(parents, start.generation())));
      }

      // Collect the offspring produced
      final List<Phenotype<G, C>> offsprings = new ArrayList<>(populationSize());
      for (Future<Phenotype<G, C>> future : futures) {
        offsprings.add(future.get());
      }

      // Step 4: evaluate all offspring
      final Seq<Phenotype<G, C>> evaluatedOffsprings = eval(ISeq.of(offsprings));

      // Step 5: replace old population using a local elitist rule
      final AltererResult<G, C> newGen = replaceOld(population, evaluatedOffsprings);

      killCount += filtered.killCount();
      invalidCount += filtered.invalidCount();
      alterCount += newGen.alterations();

      return EvolutionResult.of(
          optimize,
          newGen.population(),
          start.generation(),
          EvolutionDurations.ZERO,
          killCount,
          invalidCount,
          alterCount);

    } catch (InterruptedException e) {
      Thread.currentThread()
            .interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Local replacement strategy. An offspring replaces its parent only if it is
   * fitter.
   */
  private AltererResult<G, C> replaceOld(final Seq<Phenotype<G, C>> parents, final Seq<Phenotype<G, C>> offsprings) {

    int alterCount = 0;
    Comparator<C> comparator = optimize.descending();

    // Start from the parent population
    MSeq<Phenotype<G, C>> next = parents.asMSeq();

    // Compare each parent with its corresponding offspring
    for (int i = 0; i < populationSize(); i++) {
      if (comparator.compare(
          parents.get(i)
                 .fitness(),
          offsprings.get(i)
                    .fitness()) > 0) {

        next.set(i, offsprings.get(i));
        ++alterCount;
      }
    }

    return new AltererResult<>(next.asISeq(), alterCount);
  }

  /**
   * Applies constraints:<br>
   * - Repairs invalid individuals<br>
   * - Replaces over-age individuals with new random ones<br>
   */
  private FilterResult<G, C> filter(final Seq<Phenotype<G, C>> population, final long generation) {

    int killCount = 0;
    int invalidCount = 0;

    final MSeq<Phenotype<G, C>> pop = MSeq.of(population);

    for (int i = 0, n = pop.size(); i < n; i++) {
      final Phenotype<G, C> ind = pop.get(i);

      if (!constraint.test(ind)) {
        pop.set(i, constraint.repair(ind, generation));
        invalidCount++;
      } else if (ind.age(generation) > evolutionParams.maximalPhenotypeAge()) {
        pop.set(i, Phenotype.of(genotypeFactory.newInstance(), generation));
        killCount++;
      }
    }

    return new FilterResult<>(pop.toISeq(), killCount, invalidCount);
  }

  /**
   * Evolves one individual by:<br>
   * - Selecting parents from local neighborhood<br>
   * - Applying alterers<br>
   */
  private Phenotype<G, C> evolveSingle(final Seq<Phenotype<G, C>> neighbors, final long generation) {

    ISeq<Phenotype<G, C>> parents = evolutionParams.offspringSelector()
                                                   .select(ISeq.of(neighbors), 2, optimize);

    AltererResult<G, C> altered = evolutionParams.alterer()
                                                 .alter(parents, generation);

    return altered.population()
                  .get(0);
  }

  /**************************************************************************
   * Evaluation method
   **************************************************************************/

  /** Delegates to evaluator for fitness evaluation. */
  @Override
  public ISeq<Phenotype<G, C>> eval(final Seq<Phenotype<G, C>> population) {
    return evaluator.eval(population);
  }

  /**************************************************************************
   * Stream creation methods
   *************************************************************************/

  /** Creates evolution stream from a supplier. */
  @Override
  public EvolutionStream<G, C> stream(final Supplier<EvolutionStart<G, C>> start) {
    return EvolutionStream.ofEvolution(() -> evolutionStart(start.get()), this);
  }

  /** Creates evolution stream from an initial population description. */
  @Override
  public EvolutionStream<G, C> stream(final EvolutionInit<G> init) {
    return stream(evolutionStart(init));
  }

  /**
   * Ensures population is filled to the correct size by creating new random
   * phenotypes if necessary.
   */
  private EvolutionStart<G, C> evolutionStart(final EvolutionStart<G, C> start) {

    final ISeq<Phenotype<G, C>> pop = start.population();
    final long gen = start.generation();

    Stream<Phenotype<G, C>> stream = Stream.concat(
        pop.stream(),
        genotypeFactory.instances()
                       .map(gt -> Phenotype.of(gt, gen)));

    ISeq<Phenotype<G, C>> full = stream.limit(populationSize())
                                       .collect(ISeq.toISeq());

    return EvolutionStart.of(full, gen);
  }

  /** Converts an EvolutionInit into an EvolutionStart. */
  private EvolutionStart<G, C> evolutionStart(final EvolutionInit<G> init) {

    final ISeq<Genotype<G>> pop = init.population();
    final long gen = init.generation();

    return evolutionStart(EvolutionStart.of(pop.map(gt -> Phenotype.of(gt, gen)), gen));
  }

  /*
   * *************************************************************************
   * Property access methods
   *************************************************************************/

  /** Returns the number of individuals, equal to the graph size. */
  public int populationSize() {
    return connections.size();
  }

  /**************************************************************************
   * Static Builder methods
   *************************************************************************/
  public static <G extends Gene<?, G>,
                 C extends Comparable<? super C>>
      Builder<G, C> builder(final Function<? super Genotype<G>, ? extends C> ff, final Factory<Genotype<G>> gtf) {

    return new Builder<>(new FitnessEvaluator<>(ff, commonPool()), gtf);
  }

  public static <T,
                 G extends Gene<?, G>,
                 C extends Comparable<? super C>>
      Builder<G, C> builder(final Function<? super T, ? extends C> ff, final Codec<T, G> codec) {

    return builder(ff.compose(codec.decoder()), codec.encoding());
  }

  public static <T,
                 G extends Gene<?, G>,
                 C extends Comparable<? super C>>
      Builder<G, C> builder(final Problem<T, G, C> problem) {

    final var builder = builder(problem.fitness(), problem.codec());
    problem.constraint()
           .ifPresent(builder::constraint);
    return builder;
  }

  /**************************************************************************
   * Engine Builder
   *************************************************************************/
  public static class Builder<G extends Gene<?, G>,
                              C extends Comparable<? super C>> {

    private final Evaluator<G, C> evaluator;
    private final Factory<Genotype<G>> genotypeFactory;

    private Constraint<G, C> constraint;
    private Optimize optimize = Optimize.MAXIMUM;
    private GraphMap connections = GraphMaps.grid(100);

    private final EvolutionParams.Builder<G, C> evolutionParams = EvolutionParams.builder();

    private ExecutorService executor = commonPool();

    public Builder(Evaluator<G, C> evaluator, Factory<Genotype<G>> gtf) {
      this.evaluator = requireNonNull(evaluator);
      this.genotypeFactory = requireNonNull(gtf);
    }

    /** Sets topology for cellular interactions. */
    public Builder<G, C> topology(final GraphMap connections) {
      this.connections = connections;
      return this;
    }

    /** Sets the selection criterion */
    public final Builder<G, C> selector(final Selector<G, C> selector) {
      evolutionParams.selector(selector);
      return this;
    }

    /** Sets mutation/crossover operators. */
    @SafeVarargs
    public final Builder<G, C> alterers(final Alterer<G, C> first, final Alterer<G, C>... rest) {

      evolutionParams.alterers(first, rest);
      return this;
    }

    public Builder<G, C> constraint(final Constraint<G, C> c) {
      this.constraint = c;
      return this;
    }

    public Builder<G, C> optimize(final Optimize o) {
      this.optimize = o;
      return this;
    }

    public Builder<G, C> maximizing() {
      return optimize(Optimize.MAXIMUM);
    }

    public Builder<G, C> minimizing() {
      return optimize(Optimize.MINIMUM);
    }

    /** Constructs the CellularEngine. */
    public CellularEngine<G, C> build() {
      return new CellularEngine<>(connections,
                                  evaluator,
                                  genotypeFactory,
                                  constraint(),
                                  optimize,
                                  evolutionParams.build(),
                                  executor);
    }

    private Constraint<G, C> constraint() {
      return constraint == null ? RetryConstraint.of(genotypeFactory) : constraint;
    }
  }
}
