package cellular;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
		SingleNodeCrossover<ProgramGene<Double>, Double> crossover = new SingleNodeCrossover<>();
		alterer = crossover.andThen(new Mutator<>());
	}

	public EvolutionStart<ProgramGene<Double>, Double> start(final int populationSize, final long generation) {

		ISeq<Phenotype<ProgramGene<Double>, Double>> population = problem.codec().encoding().instances()
				.map(gt -> Phenotype.of(gt, 0, problem.fitness(gt))).limit(populationSize).collect(ISeq.toISeq());

		return EvolutionStart.of(population, generation);
	}

	@Override
	public EvolutionResult<ProgramGene<Double>, Double> evolve(EvolutionStart<ProgramGene<Double>, Double> start) {

		int invalidCount = 0;
		int killCount = 0;
		int alterCount = 0;

		List<Phenotype<ProgramGene<Double>, Double>> offsprings = new LinkedList<>();
		final ISeq<Phenotype<ProgramGene<Double>, Double>> population = eval(start.population());
		LinkedList<Phenotype<ProgramGene<Double>, Double>> neighbors = new LinkedList<>();
		for (int i = 0; i < start.population().size(); i++) {
			connections.getConnections().get(i).stream().forEach(j -> neighbors.add(population.get(j)));
			ISeq<Phenotype<ProgramGene<Double>, Double>> parents = selector.select(Seq.of(neighbors), 2,
					Optimize.MINIMUM);

			AltererResult<ProgramGene<Double>, Double> crossed = alterer.alter(parents, start.generation());

			alterCount += crossed.alterations();

			ISeq<Phenotype<ProgramGene<Double>, Double>> nextGen = eval(crossed.population());

			Phenotype<ProgramGene<Double>, Double> newborn = nextGen.stream().sorted().collect(ISeq.toISeq()).get(0);

			if (population.get(i).fitness() > newborn.fitness()) {
				offsprings.add(newborn);
				killCount++;
			} else {
				offsprings.add(population.get(i));
			}

			neighbors.clear();
		}

//		for (int i = 0; i < offsprings.size(); i++) {
//			if (!offsprings.get(i).isValid()) {
//				invalidCount++;
//				Phenotype<ProgramGene<Double>, Double> newborn = Phenotype.of(problem.codec().encoding().newInstance(),
//						start.generation() + 1);
//				offsprings.set(i, newborn.withFitness(problem.fitness(newborn.genotype())));
//			}
//		}

		return EvolutionResult.of(Optimize.MINIMUM, ISeq.of(offsprings), start.generation() + 1,
				EvolutionDurations.ZERO, killCount, invalidCount, alterCount);

	}

	@Override
	public ISeq<Phenotype<ProgramGene<Double>, Double>> eval(Seq<Phenotype<ProgramGene<Double>, Double>> population) {

//		return population.parallelStream().map(pt -> {
//			if (!pt.isEvaluated()) {
//				return Phenotype.of(pt.genotype(), pt.generation(), problem.fitness(pt.genotype()));
//			} else {
//				return pt;
//			}
//		}).collect(ISeq.toISeq());

		try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
			List<Future<Phenotype<ProgramGene<Double>, Double>>> futures = new ArrayList<>(population.size());
			for (Phenotype<ProgramGene<Double>, Double> pt : population) {
				futures.add(pool.submit(() -> {
					if (!pt.isEvaluated()) {
						Double fitnessValue = problem.fitness(pt.genotype());
						return Phenotype.of(pt.genotype(), pt.generation(), fitnessValue);
					} else {
						return pt;
					}
				}));
			}
			ArrayList<Phenotype<ProgramGene<Double>, Double>> evaluatedPopulation = new ArrayList<>(population.size());
			for (Future<Phenotype<ProgramGene<Double>, Double>> future : futures) {
				evaluatedPopulation.add(future.get());
			}
			return ISeq.of(evaluatedPopulation);
		} catch (InterruptedException e) {
			e.printStackTrace();
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
		return null;
	}
}
