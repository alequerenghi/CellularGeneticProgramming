package examples;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import cellular.CellularEngine;
import cellular.GraphMap;
import cellular.GraphMaps;
import io.jenetics.Mutator;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.EphemeralConst;
import io.jenetics.prog.op.MathExpr;
import io.jenetics.prog.op.MathOp;
import io.jenetics.prog.op.Op;
import io.jenetics.prog.op.Var;
import io.jenetics.prog.regression.Error;
import io.jenetics.prog.regression.LossFunction;
import io.jenetics.prog.regression.Regression;
import io.jenetics.prog.regression.Sample;
import io.jenetics.util.ISeq;

/**
 * Example driver program that compares standard panmictic Genetic Programming
 * (Jenetics Engine) with various Cellular Genetic Programming structures (using
 * CellularEngine).
 *
 * This class: 1. Loads symbolic regression datasets from compressed .gz
 * tab-separated files. 2. Builds a range of graph topologies (grid, scale-free,
 * random, etc.) for cellular evolution. 3. Runs multiple evolutionary
 * repetitions for each topology. 4. Runs multiple standard GP repetitions for
 * comparison. 5. Logs best fitness, average fitness, and best evolved
 * expression. 6. Writes the results into output files.
 *
 * Each dataset file is assumed to contain a header row naming variables,
 * followed by tab-separated numeric values, compressed with gzip.
 */
public class SymbolicRegressionTest {

  /** Deterministic RNG for reproducibility. */
  private static final Random RNG = new Random(42);

  /**
   * Set of available GP operations (function set). These are the primitive
   * functions allowed in program evolution.
   */
  private static final ISeq<Op<Double>> OPS = ISeq.of(
      MathOp.ADD,
      MathOp.SUB,
      MathOp.MUL,
      MathOp.DIV,
      MathOp.SQRT,
      MathOp.EXP);

  /**
   * Predefined graph structures to test: grid, scale-free, in-out hubs, random
   * graph, small-world.
   *
   * These topologies drive the neighborhood structure of the CellularEngine.
   */
  private static final List<GraphMap> GRIDS = List.of(
      GraphMaps.grid(100),
      GraphMaps.barabasiAlbert(100, 5),
      GraphMaps.multipleInAndOutNodes(100, .3, .3, 5),
      GraphMaps.erdosRenyi(100, 0.1),
      GraphMaps.wattsStrogatz(100, 5, .1));

  /** How many independent evolutionary runs to perform per topology. */
  private static final int REPETITIONS = 10;

  /** Maximum number of generations per evolutionary run. */
  private static final int MAX_GENERATIONS = 30;

  public static void main(String... args) {

    // Collect input files from ./data/ directory (excluding directories).
    var inputFiles = Stream.of(new File("data").listFiles())
                           .filter(f -> !f.isDirectory())
                           .collect(Collectors.toSet());

    try {
      // For each dataset file
      for (File file : inputFiles) {
        final StringBuilder output = new StringBuilder();

        // Construct symbolic regression problem from dataset
        final Regression<Double> regression = generateProblem(file);

        // Run cellular GP for each topology
        for (GraphMap grid : GRIDS) {
          output.append("\n\n")
                .append(evolveMultipleTimes(regression, grid));
        }

        // Run standard Jenetics GP for comparison
        List<EvolutionResult<ProgramGene<Double>, Double>> standardResults = new ArrayList<>(REPETITIONS);

        for (int i = 0; i < REPETITIONS; i++) {

          var engine = Engine.builder(regression)
                             .minimizing()
                             .populationSize(100)
                             .alterers(new SingleNodeCrossover<>(0.1), new Mutator<>())
                             .build();

          var evolutionResult = engine.stream()
                                      .limit(MAX_GENERATIONS)
                                      .collect(EvolutionResult.toBestEvolutionResult());

          standardResults.add(evolutionResult);
        }

        // Compute statistics across standard GP runs
        double averageFitness = standardResults.stream()
                                               .mapToDouble(EvolutionResult::bestFitness)
                                               .average()
                                               .orElse(0);

        var bestPhenotype = standardResults.stream()
                                           .map(EvolutionResult::bestPhenotype)
                                           .sorted()
                                           .toList()
                                           .get(0);

        Double bestFitness = bestPhenotype.fitness();

        // Extract and simplify best GP expression
        var tree = bestPhenotype.genotype()
                                .gene()
                                .toTreeNode();
        MathExpr.rewrite(tree);

        output.append("\n\n")
              .append(
                  String.format(
                      "Standard GP:%n%nBest fitness: %.5f%nAverage fitness: %.5f%nBest individual: %s",
                      bestFitness,
                      averageFitness,
                      new MathExpr(tree).toString()));

        // Write results to output file
        writeOutput(output.toString(), file);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Runs multiple independent CellularEngine evolutionary, then aggregates
   * results: best fitness, average fitness, and best evolved expression.
   *
   * @param problem     symbolic regression instance
   * @param connections graph topology for cellular evolution
   * @return formatted string containing performance statistics
   */
  private static String evolveMultipleTimes(Regression<Double> problem, GraphMap connections) {

    // Collect all runs
    List<EvolutionResult<ProgramGene<Double>, Double>> results = new ArrayList<>(REPETITIONS);
    for (int i = 0; i < REPETITIONS; i++) {
      var engine = CellularEngine.builder(problem)
                                 .topology(connections)
                                 .minimizing()
                                 .alterers(new SingleNodeCrossover<>(.8), new Mutator<>())
                                 .build();

      var evolutionResult = engine.stream()
                                  .limit(MAX_GENERATIONS)
                                  .collect(EvolutionResult.toBestEvolutionResult());
      results.add(evolutionResult);
    }

    // Aggregate statistics
    double avgFitness = results.stream()
                               .mapToDouble(EvolutionResult::bestFitness)
                               .average()
                               .orElse(0);

    var bestPhenotype = results.stream()
                               .map(EvolutionResult::bestPhenotype)
                               .sorted()
                               .toList()
                               .get(0);

    Double bestFitness = bestPhenotype.fitness();

    var tree = bestPhenotype.genotype()
                            .gene()
                            .toTreeNode();

    MathExpr.rewrite(tree);

    return String.format(
        "Structure: %s%n%nBest fitness: %.5f%nAverage fitness: %.5f%nBest individual: %s%n%n",
        connections.toString(),
        bestFitness,
        avgFitness,
        new MathExpr(tree).toString());
  }

  /**
   * Builds a symbolic regression problem from a gzip-compressed, tab-separated
   * dataset file.
   *
   * Expected format: var1<TAB>var2<TAB>...<TAB>target v11<TAB>v12<TAB>...<TAB>t1
   * v21<TAB>v22<TAB>...<TAB>t2 ...
   *
   * @param file compressed dataset file
   * @return configured Regression object
   * @throws IOException if reading or parsing fails
   */
  private static Regression<Double> generateProblem(File file) throws IOException {

    try (
        BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file))))) {

      // First line: variable names
      String header = br.readLine();
      String[] vars = header.split("\t");

      // Terminals include variables and one ephemeral constant
      ArrayList<Op<Double>> terminals = new ArrayList<>(vars.length);
      for (int i = 0; i < vars.length - 1; i++) {
        terminals.add(Var.of(vars[i], i));
      }
      terminals.add(EphemeralConst.of(() -> RNG.nextDouble(10)));

      // Read all remaining file content, convert tabs to commas for
      // Sample.parseDoubles
      final String csvData = br.readAllAsString()
                               .replace("\t", ", ");
      List<Sample<Double>> samples = Sample.parseDoubles(csvData);

      // Build GP regression problem with max tree size constraint < 50
      return Regression.of(
          Regression.codecOf(
              OPS,
              ISeq.of(terminals),
              5,
              gt -> gt.gene()
                      .size() < 50),
          Error.of(LossFunction::mse),
          samples);
    }
  }

  /**
   * Writes the results of all runs into an output file inside ./outputs/.
   *
   * Each input dataset produces one corresponding output file.
   *
   * @param output full textual experiment log
   * @param file   input dataset file (used to name the output)
   * @throws IOException if the output directory or file cannot be created
   */
  private static void writeOutput(String output, File file) throws IOException {

    File dataDir = new File("outputs");
    if (!dataDir.exists() && !dataDir.mkdir()) {
      throw new IOException("Impossible to create 'data' folder");
    }

    File outFile = new File(dataDir,
                            file.getName()
                                .concat(".txt"));
    if (!outFile.exists() && !outFile.createNewFile()) {
      throw new IOException(String.format("Impossible to create '%s'", file));
    }

    try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {

      bw.write(output);
    }
  }
}
