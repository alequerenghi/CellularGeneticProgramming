package cellular;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

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

	private final Function<Integer, List<Integer>> connections;
	private static final Selector<ProgramGene<Double>, Double> SELECTOR = new TournamentSelector<>();
	private static final SingleNodeCrossover<ProgramGene<Double>, Double> crossover = new SingleNodeCrossover<>();
	private static final Mutator<ProgramGene<Double>, Double> mutator = new Mutator<>();
	private Problem<Tree<Op<Double>, ?>, ProgramGene<Double>, Double> problem;

	public CellularEngine(Function<Integer, List<Integer>> connections, Regression<Double> regression) {
		super();
		this.connections = connections;
		this.problem = regression;
	}

	public EvolutionStart<ProgramGene<Double>, Double> start(final int populationSize, final long generation) {

		ISeq<Phenotype<ProgramGene<Double>, Double>> population = problem.codec().encoding().instances()
				.map(gt -> Phenotype.of(gt, 0, problem.fitness(gt))).limit(populationSize).collect(ISeq.toISeq());

		return EvolutionStart.of(population, generation);
	}

	@Override
	public EvolutionResult<ProgramGene<Double>, Double> evolve(EvolutionStart<ProgramGene<Double>, Double> start) {

		List<Phenotype<ProgramGene<Double>, Double>> offsprings = new LinkedList<>();
		final ISeq<Phenotype<ProgramGene<Double>, Double>> population = eval(start.population());
		LinkedList<Phenotype<ProgramGene<Double>, Double>> neighbors = new LinkedList<>();
		for (int i = 0; i < start.population().size(); i++) {
			connections.apply(i).stream().forEach(j -> neighbors.add(population.get(j)));
			ISeq<Phenotype<ProgramGene<Double>, Double>> parents = SELECTOR.select(Seq.of(neighbors), 2,
					Optimize.MINIMUM);

			AltererResult<ProgramGene<Double>, Double> crossed = crossover.alter(parents, start.generation());

			ISeq<Phenotype<ProgramGene<Double>, Double>> nextGen = eval(
					mutator.alter(crossed.population(), start.generation()).population());

			Phenotype<ProgramGene<Double>, Double> newborn = nextGen.stream().sorted().collect(ISeq.toISeq()).get(0);

			offsprings.add(population.get(i).fitness() > newborn.fitness() ? newborn : population.get(i));

			neighbors.clear();
		}

		AltererResult<ProgramGene<Double>, Double> alter = mutator.alter(ISeq.of(offsprings), start.generation());

		return EvolutionResult.of(Optimize.MINIMUM, alter.population(), start.generation() + 1, EvolutionDurations.ZERO,
				0, 0, 0);

	}

	@Override
	public ISeq<Phenotype<ProgramGene<Double>, Double>> eval(Seq<Phenotype<ProgramGene<Double>, Double>> population) {

		LinkedList<Phenotype<ProgramGene<Double>, Double>> evaluatedPopulation = new LinkedList<>();

		for (Phenotype<ProgramGene<Double>, Double> pt : population) {
			if (!pt.isEvaluated()) {
				evaluatedPopulation.add(Phenotype.of(pt.genotype(), pt.generation(), problem.fitness(pt.genotype())));
			} else {
				evaluatedPopulation.add(pt);
			}
		}
		return ISeq.of(evaluatedPopulation);
	}
}
