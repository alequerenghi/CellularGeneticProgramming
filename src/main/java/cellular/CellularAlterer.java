package cellular;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.jenetics.Alterer;
import io.jenetics.AltererResult;
import io.jenetics.Optimize;
import io.jenetics.Phenotype;
import io.jenetics.Selector;
import io.jenetics.TournamentSelector;
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.ext.TreeGene;
import io.jenetics.util.ISeq;
import io.jenetics.util.Seq;

public class CellularAlterer<G extends TreeGene<?, G>, C extends Comparable<? super C>> implements Alterer<G, C> {

	private final Map<Integer, List<Integer>> connections;
	private final Selector<G, C> selector;
	private final SingleNodeCrossover<G, C> crossover;

	public CellularAlterer(Map<Integer, List<Integer>> connections) {
		this.connections = connections;
		selector = new TournamentSelector<>();
		crossover = new SingleNodeCrossover<>();

	}

	@Override
	public AltererResult<G, C> alter(Seq<Phenotype<G, C>> population, long generation) {

		LinkedList<Phenotype<G, C>> offspring = new LinkedList<>();
		LinkedList<Phenotype<G, C>> neighbors = new LinkedList<>();
		for (int i = 0; i < connections.size(); i++) {
			connections.get(i).stream().forEach(j -> neighbors.add(population.get(j)));
			Phenotype<G, C> other = selector.select(Seq.of(neighbors), 1, Optimize.MINIMUM).get(0);

			Phenotype<G, C> that = population.get(i);

			Phenotype<G, C> newborn = crossover.alter(Seq.of(that, other), generation).population().get(0);

			if (that.fitness().compareTo(newborn.fitness()) > 0) {
				offspring.add(population.get(i));
			} else {
				offspring.add(newborn);
			}
			neighbors.clear();
		}
		return new AltererResult<>(ISeq.of(offspring));
	}

	public Map<Integer, List<Integer>> getConnections() {
		return connections;
	}
}
