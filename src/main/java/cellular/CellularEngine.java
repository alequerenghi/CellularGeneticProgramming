package cellular;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import io.jenetics.Alterer;
import io.jenetics.AltererResult;
import io.jenetics.Mutator;
import io.jenetics.Optimize;
import io.jenetics.Phenotype;
import io.jenetics.Selector;
import io.jenetics.TournamentSelector;
import io.jenetics.engine.Evaluator;
import io.jenetics.engine.Evolution;
import io.jenetics.engine.EvolutionDurations;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.EvolutionStart;
import io.jenetics.engine.Problem;
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.ext.util.Tree;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.Op;
import io.jenetics.prog.regression.Regression;
import io.jenetics.util.ISeq;
import io.jenetics.util.Seq;

public class CellularEngine implements Evolution<ProgramGene<Double>, Double>, Evaluator<ProgramGene<Double>, Double> {

  private final GraphMap connections;
  private final Selector<ProgramGene<Double>, Double> selector;
  private final Alterer<ProgramGene<Double>, Double> alterer;
  private Problem<Tree<Op<Double>, ?>, ProgramGene<Double>, Double> problem;

  public CellularEngine(GraphMap connections, Regression<Double> regression) {
    super();
    this.connections = connections;
    this.problem = regression;
    selector = new TournamentSelector<>();
    SingleNodeCrossover<ProgramGene<Double>, Double> crossover = new SingleNodeCrossover<>(1.0);
    alterer = crossover.andThen(new Mutator<>(1.0));
  }

  public EvolutionStart<ProgramGene<Double>, Double> start(final int populationSize, final long generation) {

    ISeq<Phenotype<ProgramGene<Double>, Double>> population = problem.codec()
        .encoding()
        .instances()
        .map(gt -> Phenotype.of(gt, 0, problem.fitness(gt)))
        .limit(populationSize)
        .collect(ISeq.toISeq());

    return EvolutionStart.of(population, generation);
  }

  @Override
  public EvolutionResult<ProgramGene<Double>, Double> evolve(EvolutionStart<ProgramGene<Double>, Double> start) {
    int invalidCount = 0;
    int killCount = 0;
    int alterCount = 0;

    final ISeq<Phenotype<ProgramGene<Double>, Double>> population = eval(start.population());
    final List<Phenotype<ProgramGene<Double>, Double>> offsprings = new ArrayList<>(population.size());

    for (int i = 0; i < start.population()
        .size(); i++) {
      final List<Phenotype<ProgramGene<Double>, Double>> neighbors = new LinkedList<>();
      connections.getConnections(i)
          .stream()
          .forEach(j -> neighbors.add(population.get(j)));
//      CompletableFuture<ISeq<Phenotype<ProgramGene<Double>, Double>>> selected = CompletableFuture
//          .supplyAsync(() -> selector.select(ISeq.of(neighbors), 2, Optimize.MINIMUM));
      ISeq<Phenotype<ProgramGene<Double>, Double>> parents = selector.select(ISeq.of(neighbors), 2, Optimize.MINIMUM);
      AltererResult<ProgramGene<Double>, Double> altered = alterer.alter(parents, start.generation());
      alterCount += altered.alterations();
      ISeq<Phenotype<ProgramGene<Double>, Double>> evaluated = eval(altered.population());
      if (!evaluated.isEmpty()) {
        Phenotype<ProgramGene<Double>, Double> newborn = evaluated.stream()
            .sorted()
            .toList()
            .get(0);
        if (population.get(i)
            .fitness() > newborn.fitness()) {
          offsprings.add(newborn);
          killCount++;
        } else {
          offsprings.add(population.get(i));
        }
      }
    }
    return EvolutionResult.of(Optimize.MINIMUM, ISeq.of(offsprings), start.generation() + 1, EvolutionDurations.ZERO,
        killCount, invalidCount, alterCount);
  }

  @Override
  public ISeq<Phenotype<ProgramGene<Double>, Double>> eval(Seq<Phenotype<ProgramGene<Double>, Double>> population) {
    return population.stream()
        .map(phenotype -> {
          if (phenotype.isEvaluated()) {
            return phenotype;
          } else {
            Double fitnessValue = problem.fitness(phenotype.genotype());
            return (phenotype.withFitness(fitnessValue));
          }
        })
        .collect(ISeq.toISeq());
  }
}
