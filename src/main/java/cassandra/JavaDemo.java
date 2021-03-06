package cassandra;

import static com.datastax.spark.connector.japi.CassandraJavaUtil.javaFunctions;
import static com.datastax.spark.connector.japi.CassandraJavaUtil.mapRowTo;
import static com.datastax.spark.connector.japi.CassandraJavaUtil.mapToRow;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.Optional;

import com.datastax.driver.core.Session;
import com.datastax.spark.connector.cql.CassandraConnector;
import com.datastax.spark.connector.japi.RDDAndDStreamCommonJavaFunctions;

import scala.Tuple2;

public class JavaDemo {

	// private static final Logger logger = Logger.getLogger(JavaDemo.class);

	public static void main(String[] args) {

		SparkConf conf = new SparkConf();
		conf.setAppName("Cassandra Spark App Name");
		conf.setMaster("local[*]");
		conf.set("spark.cassandra.connection.host", "localhost");
		conf.set("spark.cassandra.connection.port", "9042");

		JavaSparkContext sc = new JavaSparkContext(conf);

		generateData(sc);
		compute(sc);
		showResults(sc);
		sc.stop();
	}

	/**
	 * 
	 * @param sc
	 */
	private static void showResults(JavaSparkContext sc) {

		JavaPairRDD<Integer, Summary> summariesRdd = javaFunctions(
				sc)	.cassandraTable("java_api", "summaries", mapRowTo(Summary.class))
					.keyBy((Summary summary) -> summary.getProduct());

		JavaPairRDD<Integer, Product> productsRdd = javaFunctions(sc)
																		.cassandraTable("java_api", "products",
																				mapRowTo(Product.class))
																		.keyBy((Product product) -> product.getId());

		List<Tuple2<Product, Optional<Summary>>> results = productsRdd	.leftOuterJoin(summariesRdd)
																		.values()
																		.collect();

		for (Tuple2<Product, Optional<Summary>> result : results) {
			System.out.println(result);
		}
	}

	/**
	 * 
	 * @param sc
	 */
	private static void compute(JavaSparkContext sc) {

		JavaPairRDD<Integer, Product> productsRDD = javaFunctions(sc)
																		.cassandraTable("java_api", "products",
																				mapRowTo(Product.class))
																		.keyBy((Product product) -> product.getId());

		JavaPairRDD<Integer, Sale> salesRDD = javaFunctions(sc)
																.cassandraTable("java_api", "sales",
																		mapRowTo(Sale.class))
																.keyBy((Sale sale) -> sale.getProduct());

		JavaPairRDD<Integer, Tuple2<Sale, Product>> joinedRDD = salesRDD.join(productsRDD);

		JavaPairRDD<Integer, BigDecimal> allSalesRDD = joinedRDD.flatMapToPair(
				(Tuple2<Integer, Tuple2<Sale, Product>> input) -> {
					Tuple2<Sale, Product> saleWithProduct = input._2();
					List<Tuple2<Integer, BigDecimal>> allSales = new ArrayList<>(saleWithProduct._2()
																								.getParents()
																								.size()
							+ 1);
					allSales.add(new Tuple2<>(saleWithProduct	._1()
																.getProduct(),
							saleWithProduct	._1()
											.getPrice()));
					for (Integer parentProduct : saleWithProduct._2()
																.getParents()) {
						allSales.add(new Tuple2<>(parentProduct, saleWithProduct._1()
																				.getPrice()));
					}

					return allSales.iterator();
				});

		JavaRDD<Summary> summariesRDD = allSalesRDD	.reduceByKey((BigDecimal v1, BigDecimal v2) -> v1.add(v2))
													.map((Tuple2<Integer, BigDecimal> input) -> new Summary(input._1(),
															input._2()));

		javaFunctions(summariesRDD)	.writerBuilder("java_api", "summaries", mapToRow(Summary.class))
									.saveToCassandra();
	}

	/**
	 * 
	 * @param sc
	 */
	private static void generateData(JavaSparkContext sc) {

		CassandraConnector connector = CassandraConnector.apply(sc.getConf());

		System.out.println("GenerateData Started...");

		// Prepare the schema
		try (Session session = connector.openSession()) {
			session.execute("DROP KEYSPACE IF EXISTS java_api");
			session.execute(
					"CREATE KEYSPACE java_api WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
			session.execute("CREATE TABLE java_api.products (id INT PRIMARY KEY, name TEXT, parents LIST<INT>)");
			session.execute("CREATE TABLE java_api.sales (id UUID PRIMARY KEY, product INT, price DECIMAL)");
			session.execute("CREATE TABLE java_api.summaries (product INT PRIMARY KEY, summary DECIMAL)");
		}

		System.out.println("Products & Sales Summeries tables are created...");

		// Prepare the products hierarchy
		List<Product> products = Arrays.asList(new Product(0, "All products", Collections.<Integer>emptyList()),
				new Product(1, "Product A", Arrays.asList(0)), new Product(4, "Product A1", Arrays.asList(0, 1)),
				new Product(5, "Product A2", Arrays.asList(0, 1)), new Product(2, "Product B", Arrays.asList(0)),
				new Product(6, "Product B1", Arrays.asList(0, 2)), new Product(7, "Product B2", Arrays.asList(0, 2)),
				new Product(3, "Product C", Arrays.asList(0)), new Product(8, "Product C1", Arrays.asList(0, 3)),
				new Product(9, "Product C2", Arrays.asList(0, 3)));

		JavaRDD<Product> productsRDD = sc.parallelize(products);

		System.out.println("after paralelize");

		RDDAndDStreamCommonJavaFunctions<Product>.WriterBuilder writerBuilder = javaFunctions(
				productsRDD).writerBuilder("java_api", "products", mapToRow(Product.class));
		
		System.out.println("before save to cassandra");

		writerBuilder.saveToCassandra();

		System.out.println("Products hierarchy prepared...");

		JavaRDD<Sale> salesRDD = productsRDD.filter((Product product) -> product.getParents()
																				.size() == 2)
											.flatMap((Product product) -> {

												Random random = new Random();
												List<Sale> sales = new ArrayList<Sale>(10);
												for (int i = 0; i < 10; i++) {
													sales.add(new Sale(UUID.randomUUID(), product.getId(),
															BigDecimal.valueOf(random.nextDouble())));
												}
												return sales.iterator();
											});

		javaFunctions(salesRDD)	.writerBuilder("java_api", "sales", mapToRow(Sale.class))
								.saveToCassandra();

		System.out.println("Sales Table Populated with Products...");
	}

	/**
	 * 
	 * @author andrei
	 *
	 */
	public static class Product implements Serializable {

		private static final long serialVersionUID = -3893364875076683712L;

		private Integer id;
		private String name;
		private List<Integer> parents;

		public Product() {
		}

		public Product(Integer id, String name, List<Integer> parents) {
			this.id = id;
			this.name = name;
			this.parents = parents;
		}

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public List<Integer> getParents() {
			return parents;
		}

		public void setParents(List<Integer> parents) {
			this.parents = parents;
		}

		@Override
		public String toString() {
			return MessageFormat.format("Product'{'id={0}, name=''{1}'', parents={2}'}'", id, name, parents);
		}
	}

	/**
	 * 
	 * @author andrei
	 *
	 */
	public static class Sale implements Serializable {

		private static final long serialVersionUID = -5862308582205961429L;

		private UUID id;
		private Integer product;
		private BigDecimal price;

		public Sale() {
		}

		public Sale(UUID id, Integer product, BigDecimal price) {
			this.id = id;
			this.product = product;
			this.price = price;
		}

		public UUID getId() {
			return id;
		}

		public void setId(UUID id) {
			this.id = id;
		}

		public Integer getProduct() {
			return product;
		}

		public void setProduct(Integer product) {
			this.product = product;
		}

		public BigDecimal getPrice() {
			return price;
		}

		public void setPrice(BigDecimal price) {
			this.price = price;
		}

		@Override
		public String toString() {
			return MessageFormat.format("Sale'{'id={0}, product={1}, price={2}'}'", id, product, price);
		}
	}

	/**
	 * 
	 * @author andrei
	 *
	 */
	public static class Summary implements Serializable {

		private static final long serialVersionUID = -837379470217195195L;

		private Integer product;
		private BigDecimal summary;

		public Summary() {
		}

		public Summary(Integer product, BigDecimal summary) {
			this.product = product;
			this.summary = summary;
		}

		public Integer getProduct() {
			return product;
		}

		public void setProduct(Integer product) {
			this.product = product;
		}

		public BigDecimal getSummary() {
			return summary;
		}

		public void setSummary(BigDecimal summary) {
			this.summary = summary;
		}

		@Override
		public String toString() {
			return MessageFormat.format("Summary'{'product={0}, summary={1}'}'", product, summary);
		}
	}

	
}