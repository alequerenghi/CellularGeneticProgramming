package cellular;

import java.util.List;
import java.util.Map;

/**
 * A lightweight wrapper around an adjacency map representing a graph topology.
 * 
 * <p>
 * This class is used by the cellular evolutionary engine to determine the
 * neighborhood structure of a population. Each node in the evolutionary system
 * corresponds to an integer index, and the map stores, for each node, the list
 * of neighboring nodes it can interact with.
 * </p>
 *
 * <p>
 * The graph is assumed to be static after construction: no dynamic modification
 * of the topology is supported. This immutability simplifies reasoning about
 * neighbor relationships during cellular evolution.
 * </p>
 */
public class GraphMap {

  /**
   * The underlying adjacency structure.
   * 
   * Keys represent node indices. Values represent the list of neighbors that the
   * node can interact with.
   */
  private final Map<Integer, List<Integer>> map;

  /**
   * A human-readable name for this graph (e.g., "Grid 20x20", "Barabasi-Albert
   * (n=200, m=3)").
   * 
   * Mainly used for logging or debugging purposes.
   */
  private final String mapName;

  /**
   * Creates a new GraphMap with the given adjacency structure and name.
   *
   * @param map     the adjacency list describing the graph topology; the map
   *                should contain one entry for each node index
   * @param mapName a label for this graph that will be returned by toString()
   */
  protected GraphMap(Map<Integer, List<Integer>> map, String mapName) {
    this.map = map;
    this.mapName = mapName;
  }

  /**
   * Returns the list of neighboring nodes for a given node.
   *
   * <p>
   * The returned list describes the connectivity pattern used by the cellular
   * engine when selecting parents for local evolution.
   * </p>
   *
   * @param node the index of the node whose neighbors are requested
   * @return the list of neighbor indices
   */
  public List<Integer> getConnections(int node) {
    return map.get(node);
  }

  public Map<Integer, List<Integer>> getMap() {
    return map;
  }

  /**
   * Returns a human-readable name for this graph.
   *
   * <p>
   * This can be useful for logging the topology currently used by the
   * evolutionary engine.
   * </p>
   *
   * @return the name assigned to this graph
   */
  @Override
  public String toString() {
    return mapName;
  }

  /**
   * Returns the number of nodes in this graph.
   *
   * <p>
   * This value typically corresponds exactly to the size of the evolutionary
   * population using the topology.
   * </p>
   *
   * @return the number of nodes in the graph
   */
  public int size() {
    return map.size();
  }
}
