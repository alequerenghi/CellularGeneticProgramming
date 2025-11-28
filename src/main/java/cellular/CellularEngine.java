package cellular;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import io.jenetics.AltererResult;
import io.jenetics.Genotype;
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
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.ext.TreeGene;
import io.jenetics.prog.ProgramChromosome;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.Op;
import io.jenetics.util.Factory;
import io.jenetics.util.ISeq;
import io.jenetics.util.Seq;

public class CellularEngine<G extends TreeGene<?, G>, C extends Comparable<? super C>>
		implements Evolution<G, C>, Evaluator<G, C> {

	private final Function<Integer, List<Integer>> connections;
	private final Selector<G, C> selector;
	private final SingleNodeCrossover<G, C> crossover;
	private final Mutator<G, C> mutator;
	private int populationSize;
	private final Function<Genotype<G>, C> fitness;

	public CellularEngine(int populationSize, final Function<Integer, List<Integer>> connections,
			Function<Genotype<G>, C> fitness) {
		this.connections = connections;
		selector = new TournamentSelector<>();
		crossover = new SingleNodeCrossover<>();
		mutator = new Mutator<>();
		this.populationSize = populationSize;
		this.fitness = fitness;
	}

	public EvolutionStart<ProgramGene<C>, C> start(int depth, ISeq<Op<C>> operations, ISeq<Op<C>> terminals) {

		LinkedList<Phenotype<ProgramGene<C>, C>> population = new LinkedList<>();
		final Factory<Genotype<ProgramGene<C>>> factory = Genotype
				.of(ProgramChromosome.of(depth, operations, terminals));
		for (int i = 0; i < populationSize; i++) {
			population.add(Phenotype.of(factory.newInstance(), 0));
		}

		return EvolutionStart.of(ISeq.of(population), 1);
	}

	@Override
	public EvolutionResult<G, C> evolve(EvolutionStart<G, C> start) {
		ISeq<Phenotype<G, C>> population = eval(start.population());
		List<Phenotype<G, C>> offspring = new ArrayList<>(population.size());
		LinkedList<Phenotype<G, C>> neighbors = new LinkedList<>();
		for (int i = 0; i < populationSize; i++) {
			connections.apply(i).stream().forEach(j -> neighbors.add(population.get(j)));
			final Phenotype<G, C> other = selector.select(Seq.of(neighbors), 1, Optimize.MINIMUM).get(0);
			final Phenotype<G, C> that = population.get(i);

			AltererResult<G, C> crossed = crossover.alter(Seq.of(that, other), start.generation());

			ISeq<Phenotype<G, C>> nextGen = eval(mutator.alter(crossed.population(), start.generation()).population());

			Phenotype<G, C> newborn = nextGen.stream().sorted(Optimize.MINIMUM.ascending()).collect(ISeq.toISeq())
					.get(0);

			if (that.fitness().compareTo(newborn.fitness()) > 0) {
				offspring.add(i, that);
			} else {
				offspring.add(newborn);
			}
			neighbors.clear();
		}

		return EvolutionResult.of(Optimize.MINIMUM, ISeq.of(offspring), start.generation() + 1, EvolutionDurations.ZERO,
				0, 0, 0);
	}

	@Override
	public ISeq<Phenotype<G, C>> eval(Seq<Phenotype<G, C>> population) {

		LinkedList<Phenotype<G, C>> evaluatedPopulation = new LinkedList<>();

		for (Phenotype<G, C> pt : population) {
			if (!pt.isEvaluated()) {
				evaluatedPopulation.add(Phenotype.of(pt.genotype(), pt.generation(), fitness.apply(pt.genotype())));
			} else {
				evaluatedPopulation.add(pt);
			}
		}
		return ISeq.of(evaluatedPopulation);
	}
}
