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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import cellular.CellularEngine;
import cellular.GraphMap;
import cellular.GraphMaps;
import io.jenetics.Mutator;
import io.jenetics.Phenotype;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.ext.util.TreeNode;
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

public class SymbolicRegressionTest {

  private static final Random random = new Random(42);

  private static final ISeq<Op<Double>> OPS = ISeq.of(
      MathOp.ADD,
      MathOp.SUB,
      MathOp.MUL,
      MathOp.DIV,
      MathOp.SQRT,
      MathOp.EXP);

  private static final List<GraphMap> GRIDS = List.of(
      GraphMaps.grid(100),
      GraphMaps.barabasiAlbert(100, 5),
      GraphMaps.multipleInAndOutNodes(100, .3, .3, 5),
      GraphMaps.erdosRenyi(100, 0.1),
      GraphMaps.wattsStrogatz(100, 5, .1));

  public static void main(String... args) {

    Set<File> inputFiles = Stream.of(new File("data").listFiles())
                                 .filter(f -> !f.isDirectory())
                                 .collect(Collectors.toSet());

    try (ExecutorService pool = Executors.newFixedThreadPool(
        Runtime.getRuntime()
               .availableProcessors())) {

      for (File file : inputFiles) {
        final StringBuilder output = new StringBuilder();

        final Regression<Double> regression = generateProblem(file);

        for (GraphMap grid : GRIDS) {

          output.append("\n\n")
                .append(evolveAsync(regression, grid, pool));
        }

        List<EvolutionResult<ProgramGene<Double>, Double>> standardResults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
          Engine<ProgramGene<Double>, Double> engine = Engine.builder(regression)
                                                             .minimizing()
                                                             .populationSize(100)
                                                             .alterers(new SingleNodeCrossover<>(0.1), new Mutator<>())
                                                             .build();
          EvolutionResult<ProgramGene<Double>, Double> er = engine.stream()
                                                                  .limit(50)
                                                                  .collect(EvolutionResult.toBestEvolutionResult());
          standardResults.add(er);
        }
        double averageFitness = standardResults.stream()
                                               .mapToDouble(EvolutionResult::bestFitness)
                                               .average()
                                               .orElse(0);
        Phenotype<ProgramGene<Double>, Double> bestPhenotype = standardResults.stream()
                                                                              .map(EvolutionResult::bestPhenotype)
                                                                              .sorted()
                                                                              .toList()
                                                                              .get(0);

        Double bestFitness = bestPhenotype.fitness();

        TreeNode<Op<Double>> tree = bestPhenotype.genotype()
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
        writeOutput(output.toString(), file);
      }
    } catch (InterruptedException e) {
      Thread.currentThread()
            .interrupt();
      e.printStackTrace();
    } catch (IOException | ExecutionException e) {
      e.printStackTrace();
    }
  }

  private static String evolveAsync(Regression<Double> problem, GraphMap connections, ExecutorService pool)
      throws InterruptedException, ExecutionException {
    List<Future<EvolutionResult<ProgramGene<Double>, Double>>> futures = new ArrayList<>(10);
    for (int i = 0; i < 10; i++) {
      futures.add(pool.submit(() -> {
        CellularEngine<ProgramGene<Double>, Double> engine = CellularEngine.builder(problem)
                                                                           .topology(connections)
                                                                           .minimizing()
                                                                           .alterers(
                                                                               new SingleNodeCrossover<>(.8),
                                                                               new Mutator<>())
                                                                           .build();
        return engine.stream()
                     .limit(50)
                     .collect(EvolutionResult.toBestEvolutionResult());
      }));
    }

    List<EvolutionResult<ProgramGene<Double>, Double>> results = new ArrayList<>(10);
    for (int i = 0; i < futures.size(); i++) {
      results.add(
          futures.get(i)
                 .get());
    }
    double avgFitness = results.stream()
                               .mapToDouble(EvolutionResult::bestFitness)
                               .average()
                               .orElse(0);
    Phenotype<ProgramGene<Double>, Double> bestPhenotype = results.stream()
                                                                  .map(EvolutionResult::bestPhenotype)
                                                                  .sorted()
                                                                  .toList()
                                                                  .get(0);
    Double bestFitness = bestPhenotype.fitness();
    TreeNode<Op<Double>> tree = bestPhenotype.genotype()
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

  private static Regression<Double> generateProblem(File file) throws IOException {

    try (
        BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file))))) {
      String header = br.readLine();

      String[] vars = header.split("\t");

      ArrayList<Op<Double>> terminals = new ArrayList<>(vars.length);
      for (int i = 0; i < vars.length - 1; i++) {
        terminals.add(Var.of(vars[i], i));
      }
      terminals.add(EphemeralConst.of(() -> random.nextDouble(10)));

      final String csvData = br.readAllAsString()
                               .replace("\t", ", ");
      List<Sample<Double>> samples = Sample.parseDoubles(csvData);
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

  private static void writeOutput(String output, File file) throws IOException {
    File dataDir = new File("outputs");
    if (!dataDir.exists() && !dataDir.mkdir()) {
      throw new IOException("Impossible to craete 'data' foler");
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
