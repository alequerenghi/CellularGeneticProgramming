package cellular;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.ForkJoinPool.commonPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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

public class CellularEngine<G extends Gene<?, G>, C extends Comparable<? super C>>
    implements
    Evolution<G, C>,
    Evaluator<G, C>,
    EvolutionStreamable<G, C> {

  private final GraphMap connections;
  private final Evaluator<G, C> evaluator;
  private final Factory<Genotype<G>> genotypeFactory;
  private final Constraint<G, C> constraint;
  private final Optimize optimize;
  private final EvolutionParams<G, C> evolutionParams;
  private final Executor pool;

  CellularEngine(
                 GraphMap connections,
                 Evaluator<G, C> evaluator,
                 Factory<Genotype<G>> genotypeFactory,
                 Constraint<G, C> constraint,
                 Optimize optimize,
                 EvolutionParams<G, C> evolutionParams,
                 Executor pool) {
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
    long generation = start.generation();
    List<Phenotype<G, C>> offsprings = new ArrayList<>(populationSize());
    for (int i = 0; i < populationSize(); i++) {
      List<Phenotype<G, C>> parents = new LinkedList<>();
      connections.getConnections(i)
                 .forEach(j -> parents.add(start.population()
                                                .get(j)));
      ISeq<Phenotype<G, C>> toSelect = parents.isEmpty() ? ISeq.of(start.population()
                                                                        .get(i))
          : ISeq.of(parents);
      CompletableFuture<Seq<Phenotype<G, C>>> selectedOffspring
          = CompletableFuture.supplyAsync(() -> select(toSelect), pool);

      CompletableFuture<AltererResult<G, C>> alteredPopulation
          = selectedOffspring.thenApplyAsync(sel -> evolutionParams.alterer()
                                                                   .alter(sel,
                                                                          generation),
                                             pool);

      CompletableFuture<Phenotype<G, C>> newborn
          = alteredPopulation.thenApply(alt -> alt.population()
                                                  .get(0));
      offsprings.add(newborn.join());
    }
    CompletableFuture<FilterResult<G, C>> filteredOffpsrings
        = CompletableFuture.supplyAsync(() -> filter(ISeq.of(offsprings), generation),
                                        pool);

    CompletableFuture<FilterResult<G, C>> filteredPopulation
        = CompletableFuture.supplyAsync(() -> filter(start.population(), generation),
                                        pool);

    CompletableFuture<ISeq<Phenotype<G, C>>> evaluatedOffsprings
        = filteredOffpsrings.thenApplyAsync(fil -> eval(fil.population()), pool);

    CompletableFuture<ISeq<Phenotype<G, C>>> evaluatedPopulation
        = filteredPopulation.thenApplyAsync(fPop -> eval(fPop.population()), pool);

    CompletableFuture<AltererResult<G, C>> newPopulation
        = evaluatedPopulation.thenCombineAsync(evaluatedOffsprings,
                                               this::replaceOld,
                                               pool);

    killCount = filteredPopulation.join()
                                  .killCount()
                + filteredOffpsrings.join()
                                    .killCount();

    invalidCount = filteredPopulation.join()
                                     .invalidCount()
                   + filteredOffpsrings.join()
                                       .invalidCount();
    alterCount += newPopulation.join()
                               .alterations();

    return EvolutionResult.of(optimize,
                              newPopulation.join()
                                           .population(),
                              start.generation(),
                              EvolutionDurations.ZERO,
                              killCount,
                              invalidCount,
                              alterCount);
  }

  private Seq<Phenotype<G, C>> select(ISeq<Phenotype<G, C>> toSelect) {
    return evolutionParams.offspringSelector()
                          .select(ISeq.of(toSelect), 2, optimize);
  }

  private AltererResult<G, C> replaceOld(
      final Seq<Phenotype<G, C>> parents,
      final Seq<Phenotype<G, C>> offsprings) {
    int alterCount = 0;
    Comparator<C> comparator = optimize.descending();
    MSeq<Phenotype<G, C>> next = parents.asMSeq();
    for (int i = 0; i < populationSize(); i++) {
      if (comparator.compare(parents.get(i)
                                    .fitness(),
                             offsprings.get(i)
                                       .fitness())
          > 0) {
        next.set(i, offsprings.get(i));
        ++alterCount;
      }
    }
    return new AltererResult<>(next.asISeq(), alterCount);
  }

  private FilterResult<G, C> filter(
      final Seq<Phenotype<G, C>> population,
      final long generation) {
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

  /**************************************************************************
   * Evaluation method
   **************************************************************************/

  @Override
  public ISeq<Phenotype<G, C>> eval(final Seq<Phenotype<G, C>> population) {
    return evaluator.eval(population);
  }

  /**************************************************************************
   * Stream creation methods
   *************************************************************************/

  @Override
  public EvolutionStream<G, C> stream(final Supplier<EvolutionStart<G, C>> start) {
    return EvolutionStream.ofEvolution(() -> evolutionStart(start.get()), this);
  }

  @Override
  public EvolutionStream<G, C> stream(final EvolutionInit<G> init) {
    return stream(evolutionStart(init));
  }

  private EvolutionStart<G, C> evolutionStart(final EvolutionStart<G, C> start) {
    final ISeq<Phenotype<G, C>> pop = start.population();
    final long gen = start.generation();
    Stream<Phenotype<G, C>> stream
        = Stream.concat(pop.stream(),
                        genotypeFactory.instances()
                                       .map(gt -> Phenotype.of(gt, gen)));
    ISeq<Phenotype<G, C>> full = stream.limit(populationSize())
                                       .collect(ISeq.toISeq());
    return EvolutionStart.of(full, gen);
  }

  private EvolutionStart<G, C> evolutionStart(final EvolutionInit<G> init) {

    final ISeq<Genotype<G>> pop = init.population();
    final long gen = init.generation();

    return evolutionStart(EvolutionStart.of(pop.map(gt -> Phenotype.of(gt, gen)), gen));
  }

  /**************************************************************************
   * Property access methods
   *************************************************************************/

  public int populationSize() {
    return connections.size();
  }

  /**************************************************************************
   * Static Builder methods
   *************************************************************************/

  public static <G extends Gene<?, G>,
                 C extends Comparable<? super C>> Builder<G, C> builder(
                     final Function<? super Genotype<G>, ? extends C> ff,
                     final Factory<Genotype<G>> gtf) {

    return new Builder<>(new FitnessEvaluator<>(ff, commonPool()), gtf);
  }

  public static <T,
                 G extends Gene<?, G>,
                 C extends Comparable<? super C>> Builder<G, C> builder(
                     final Function<? super T, ? extends C> ff,
                     final Codec<T, G> codec) {

    return builder(ff.compose(codec.decoder()), codec.encoding());
  }

  public static <T,
                 G extends Gene<?, G>,
                 C extends Comparable<? super C>> Builder<G, C> builder(
                     final Problem<T, G, C> problem) {

    final var builder = builder(problem.fitness(), problem.codec());
    problem.constraint()
           .ifPresent(builder::constraint);
    return builder;
  }

  /**************************************************************************
   * Engine Builder
   *************************************************************************/

  public static class Builder<G extends Gene<?, G>, C extends Comparable<? super C>> {

    private final Evaluator<G, C> evaluator;
    private final Factory<Genotype<G>> genotypeFactory;

    private Constraint<G, C> constraint;
    private Optimize optimize = Optimize.MAXIMUM;
    private GraphMap connections = GraphMaps.grid(100);

    private final EvolutionParams.Builder<G, C> evolutionParams
        = EvolutionParams.builder();

    private ExecutorService executor = commonPool();

    public Builder(Evaluator<G, C> evaluator, Factory<Genotype<G>> gtf) {
      this.evaluator = requireNonNull(evaluator);
      this.genotypeFactory = requireNonNull(gtf);
    }

    public Builder<G, C> topology(final GraphMap connections) {
      this.connections = connections;
      return this;
    }

    public final Builder<G, C> selector(final Selector<G, C> selector) {
      evolutionParams.selector(selector);
      return this;
    }

    @SafeVarargs
    public final Builder<G, C> alterers(
        final Alterer<G, C> first,
        final Alterer<G, C>... rest) {

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
