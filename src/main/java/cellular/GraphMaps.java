package cellular;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import io.jenetics.util.RandomRegistry;

public class GraphMaps {

	private static final RandomGenerator RNG = RandomRegistry.random();

	private GraphMaps() {
		// nothing
	}

	public static GraphMap grid(int popSize) {
		int gridSize = (int) Math.sqrt(popSize);

		HashMap<Integer, List<Integer>> map = new HashMap<>();
		for (int i = 0; i < popSize; i++) {
			ArrayList<Integer> neighbors = new ArrayList<>();
			neighbors.add((i + 1) % popSize);
			neighbors.add(i - 1 < 0 ? popSize - 1 : i - 1);
			neighbors.add((i + gridSize) % popSize);
			neighbors.add(i - gridSize < 0 ? popSize - gridSize + i : i - gridSize);
			map.put(i, neighbors);
		}
		return new GraphMap(map, "grid");
	}

	public static GraphMap multipleInAndOutNodes(int numNodes, double highInFraction, double highOutFraction,
			int avgDegree) {

		Map<Integer, List<Integer>> graph = new HashMap<>();

		// Initialize empty adjacency lists
		for (int i = 0; i < numNodes; i++) {
			graph.put(i, new ArrayList<>());
		}

		// Pick hub nodes
		int numInHubs = (int) (numNodes * highInFraction);
		int numOutHubs = (int) (numNodes * highOutFraction);

		Set<Integer> inHubs = pickRandomSet(numNodes, numInHubs);
		Set<Integer> outHubs = pickRandomSet(numNodes, numOutHubs);

		// Build edges
		for (int from = 0; from < numNodes; from++) {

			int degree;

			if (outHubs.contains(from)) {
				// Out-hubs have more outgoing edges
				degree = avgDegree * 4;
			} else {
				// Normal nodes
				degree = avgDegree;
			}

			for (int k = 0; k < degree; k++) {
				int to = RNG.nextInt(numNodes);
				if (to == from)
					continue; // avoid self-loop

				graph.get(from).add(to);
			}
		}

		// Add extra incoming edges for in-hubs
		for (int hub : inHubs) {
			int extraIn = avgDegree * 4;

			for (int k = 0; k < extraIn; k++) {
				int from = RNG.nextInt(numNodes);
				if (from == hub)
					continue;

				graph.get(from).add(hub);

			}
		}
		return new GraphMap(graph, "Multiple in- and out-nodes");
	}

	public static GraphMap barabasiAlbert(int graphSize, int m) {
		int m0 = RNG.nextInt(m, 2 * m);

		Map<Integer, List<Integer>> g = new HashMap<>();

		for (int i = 0; i < graphSize; i++) {
			g.put(i, new ArrayList<>());
		}

		for (int i = 0; i < m0; i++) {
			for (int j = 0; j < m0; j++) {
				if (i != j) {
					g.get(i).add(j);
					g.get(j).add(i);
				}
			}
		}

		while (g.size() < graphSize) {
			int current = g.size();
			g.put(current, new ArrayList<>());
			List<Integer> connections = g.values().stream().map(List::size)
					.collect(Collectors.toCollection(ArrayList::new));
			int nConnections = connections.stream().mapToInt(Integer::intValue).sum();
			for (int i = 0; i < m; i++) {
				int chosen = proportionalSelection(connections, nConnections);
				g.get(chosen).add(current);
				g.get(current).add(chosen);
				connections.set(chosen, connections.get(chosen) + 1);
				connections.set(current, connections.get(current) + 1);
				nConnections += 2;
			}
		}
		return new GraphMap(g, "Barbasi Albert");
	}

	private static int proportionalSelection(List<Integer> weights, int weightsSum) {
		double r = RNG.nextDouble() * weightsSum;
		int i;
		for (i = 0; i < weights.size() && r > 0; i++) {
			r -= weights.get(i);
		}
		return i;
	}

	public static GraphMap wattsStrogatz(int graphSize, int k, double beta) {

		Map<Integer, List<Integer>> g = new HashMap<>();

		for (int i = 0; i < graphSize; i++) {
			g.put(i, new ArrayList<>());
			for (int j = 1; j <= k / 2; j++) {
				int neighbor = (i + j) % graphSize;
				g.get(i).add(neighbor);
			}
		}

		for (int i = 0; i < graphSize; i++) {

			List<Integer> outs = g.get(i);

			for (int idx = 0; idx < outs.size(); idx++) {
				if (RNG.nextDouble() < beta) {
					int newTarget;
					do {
						newTarget = RNG.nextInt(graphSize);
					} while (newTarget == i || outs.contains(newTarget));

					outs.set(idx, newTarget);
				}
			}
		}
		return new GraphMap(g, "Watts Strogatz");
	}

	public static GraphMap erdosRenyi(int n, double p) {
		Map<Integer, List<Integer>> g = new HashMap<>();

		for (int i = 0; i < n; i++) {
			g.put(i, new ArrayList<>());
			for (int j = 0; j < n; j++) {
				if (i != j && RNG.nextDouble() < p) {
					g.get(i).add(j);
				}
			}
		}
		return new GraphMap(g, "Erdos Renyi");
	}

	public static GraphMap layeredDAG(final int layers, final int nodesPerLayer, final double forwardProb) {

		int total = layers * nodesPerLayer;
		Map<Integer, List<Integer>> g = new HashMap<>();

		for (int i = 0; i < total; i++) {
			g.put(i, new ArrayList<>());
		}

		for (int l = 0; l < layers - 1; l++) {
			for (int i = l * nodesPerLayer; i < (l + 1) * nodesPerLayer; i++) {
				for (int j = (l + 1) * nodesPerLayer; j < (l + 2) * nodesPerLayer; j++) {
					if (RNG.nextDouble() < forwardProb) {
						g.get(i).add(j);
					}
				}
			}
		}
		return new GraphMap(g, "Layered Directed Acyclic graph");
	}

	private static Set<Integer> pickRandomSet(int max, int count) {
		Set<Integer> set = new HashSet<>();
		while (set.size() < count) {
			set.add(RNG.nextInt(max));
		}
		return set;
	}

}
