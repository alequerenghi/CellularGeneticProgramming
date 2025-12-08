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

public class CellularEngine<G extends Gene<?, G>, C extends Comparable<? super C>>
    implements Evolution<G, C>, Evaluator<G, C>, EvolutionStreamable<G, C> {

  private final GraphMap connections;

  private final Evaluator<G, C> evaluator;
  private final Factory<Genotype<G>> genotypeFactory;
  private final Constraint<G, C> constraint;
  private final Optimize optimize;

  // Evolution parameters.
  private final EvolutionParams<G, C> evolutionParams;

  // Execution context for concurrent execution of evolving steps.
  private final ExecutorService pool;
//  private final InstantSource _clock;
//  private final EvolutionInterceptor<G, C> interceptor;

  CellularEngine(GraphMap connections,
                 Evaluator<G, C> evaluator,
                 Factory<Genotype<G>> genotypeFactory,
                 Constraint<G, C> constraint,
                 Optimize optimize,
                 EvolutionParams<G, C> evolutionParams,
                 ExecutorService pool) {
    super();
    this.evaluator = evaluator;
    this.connections = connections;
    this.genotypeFactory = genotypeFactory;
    this.constraint = constraint;
    this.optimize = optimize;
    this.evolutionParams = evolutionParams;
    this.pool = pool;
  }

  @Override
  public EvolutionResult<G, C> evolve(final EvolutionStart<G, C> start) {
    int invalidCount = 0;
    int killCount = 0;
    int alterCount = 0;

    try {
      final ISeq<Phenotype<G, C>> population = eval(start.population());

      final int popSize = start.population()
                               .size();

      final FilterResult<G, C> filteredPopulation = filter(population, start.generation());
      final List<Future<Phenotype<G, C>>> futures = new ArrayList<>(popSize);
      for (int i = 0; i < popSize; i++) {
        final List<Phenotype<G, C>> neighbors = new LinkedList<>();
        connections.getConnections(i)
                   .stream()
                   .forEach(
                       j -> neighbors.add(
                           filteredPopulation.population()
                                             .get(j)));
        futures.add(pool.submit(() -> evolveSingle(start, neighbors)));
      }
      final List<Phenotype<G, C>> offsprings = new ArrayList<>(popSize);
      for (Future<Phenotype<G, C>> future : futures) {
        offsprings.add(future.get());
      }

      ISeq<Phenotype<G, C>> evaluatedOffsprings = eval(ISeq.of(offsprings));
      AltererResult<G, C> newGeneration = replaceOld(filteredPopulation.population(), evaluatedOffsprings);

      killCount += filteredPopulation.killCount();
      invalidCount += filteredPopulation.invalidCount();
      alterCount += newGeneration.alterations();

      return EvolutionResult.of(
          optimize,
          newGeneration.population(),
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

  private AltererResult<G, C> replaceOld(
      final ISeq<Phenotype<G, C>> population,
      final ISeq<Phenotype<G, C>> evaluatedOffsprings) {
    int alterCount = 0;
    Comparator<C> comparator = optimize.descending();
    MSeq<Phenotype<G, C>> offsprings = MSeq.of(population);
    for (int i = 0; i < populationSize(); i++) {
      if (comparator.compare(
          population.get(i)
                    .fitness(),
          evaluatedOffsprings.get(i)
                             .fitness()) > 0) {
        offsprings.set(i, evaluatedOffsprings.get(i));
        ++alterCount;
      }
    }
    return new AltererResult<>(population, alterCount);
  }

  private FilterResult<G, C> filter(final Seq<Phenotype<G, C>> population, final long generation) {
    int killCount = 0;
    int invalidCount = 0;

    final MSeq<Phenotype<G, C>> pop = MSeq.of(population);
    for (int i = 0, n = pop.size(); i < n; ++i) {
      final Phenotype<G, C> individual = pop.get(i);

      if (!constraint.test(individual)) {
        pop.set(i, constraint.repair(individual, generation));
        ++invalidCount;
      } else if (individual.age(generation) > evolutionParams.maximalPhenotypeAge()) {
        pop.set(i, Phenotype.of(genotypeFactory.newInstance(), generation));
        ++killCount;
      }
    }

    return new FilterResult<>(pop.toISeq(), killCount, invalidCount);
  }

  private Phenotype<G, C> evolveSingle(EvolutionStart<G, C> start, final List<Phenotype<G, C>> neighbors) {
    ISeq<Phenotype<G, C>> parents = evolutionParams.offspringSelector()
                                                   .select(ISeq.of(neighbors), 2, optimize);
    AltererResult<G, C> altered = evolutionParams.alterer()
                                                 .alter(parents, start.generation());
    return altered.population()
                  .get(0);
  }

  @Override
  public ISeq<Phenotype<G, C>> eval(Seq<Phenotype<G, C>> population) {
    return evaluator.eval(population);
  }

  @Override
  public EvolutionStream<G, C> stream(Supplier<EvolutionStart<G, C>> start) {
    return EvolutionStream.ofEvolution(() -> evolutionStart(start.get()), this);
  }

  @Override
  public EvolutionStream<G, C> stream(EvolutionInit<G> init) {
    return stream(evolutionStart(init));
  }

  private EvolutionStart<G, C> evolutionStart(final EvolutionStart<G, C> start) {
    final ISeq<Phenotype<G, C>> population = start.population();
    final long gen = start.generation();

    final Stream<Phenotype<G, C>> stream = Stream.concat(
        population.stream(),
        genotypeFactory.instances()
                       .map(gt -> Phenotype.of(gt, gen)));

    final ISeq<Phenotype<G, C>> pop = stream.limit(populationSize())
                                            .collect(ISeq.toISeq());

    return EvolutionStart.of(pop, gen);
  }

  private EvolutionStart<G, C> evolutionStart(final EvolutionInit<G> init) {
    final ISeq<Genotype<G>> pop = init.population();
    final long gen = init.generation();

    return evolutionStart(EvolutionStart.of(pop.map(gt -> Phenotype.of(gt, gen)), gen));
  }

  public int populationSize() {
    return connections.size();
  }

  public static <G extends Gene<?, G>, C extends Comparable<? super C>> Builder<G, C> builder(
      final Function<? super Genotype<G>, ? extends C> ff,
      final Factory<Genotype<G>> gtf) {
    return new Builder<>(new FitnessEvaluator<>(ff, commonPool()), gtf);
  }

  public static <T, G extends Gene<?, G>, C extends Comparable<? super C>> Builder<G, C> builder(
      final Function<? super T, ? extends C> ff,
      final Codec<T, G> codec) {
    return builder(ff.compose(codec.decoder()), codec.encoding());
  }

  public static <T, G extends Gene<?, G>, C extends Comparable<? super C>> Builder<G, C> builder(
      final Problem<T, G, C> problem) {
    final var builder = builder(problem.fitness(), problem.codec());
    problem.constraint()
           .ifPresent(builder::constraint);
    return builder;
  }

  public static class Builder<G extends Gene<?, G>, C extends Comparable<? super C>> {

    private final Evaluator<G, C> evaluator;
    private final Factory<Genotype<G>> genotypeFactory;
    private Constraint<G, C> constraint;
    private Optimize optimize = Optimize.MAXIMUM;
    private GraphMap connections = GraphMaps.grid(100);

    // Evolution parameters.
    private final EvolutionParams.Builder<G, C> evolutionParams = EvolutionParams.builder();

    // Engine execution environment.
    private ExecutorService executor = commonPool();
//    private BatchExecutor _fitnessExecutor = null;
//    private InstantSource _clock = NanoClock.systemUTC();
//
//    private EvolutionInterceptor<G, C> _interceptor = EvolutionInterceptor.identity();

    public Builder(final Evaluator<G, C> evaluator, final Factory<Genotype<G>> gtf) {
      genotypeFactory = requireNonNull(gtf);
      this.evaluator = requireNonNull(evaluator);
    }

    public Builder<G, C> topology(GraphMap connections) {
      this.connections = connections;
      return this;
    }

    @SafeVarargs
    public final Builder<G, C> alterers(final Alterer<G, C> first, final Alterer<G, C>... rest) {
      evolutionParams.alterers(first, rest);
      return this;
    }

    public Builder<G, C> constraint(final Constraint<G, C> constraint) {
      this.constraint = constraint;
      return this;
    }

    public Builder<G, C> optimizing(Optimize optimize) {
      this.optimize = optimize;
      return this;
    }

    public Builder<G, C> maximizing() {
      return optimizing(Optimize.MAXIMUM);
    }

    public Builder<G, C> minimizing() {
      return optimizing(Optimize.MINIMUM);
    }

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
