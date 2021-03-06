package objectlm;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import numpy_to_ejml.MatrixImporter;
import objectlm.utils.HierarchicalCluster;
import objectlm.utils.Triple;
//import objectlm.utils.Tuple;

import objectlm.utils.Tuple;
import objectlm.utils.VectorUtils;

import org.ejml.simple.SimpleMatrix;

import java.util.List;

public class ObjectLM implements Serializable {
	/* This class handles the model parameters
	 * and gives access to search methods on the
	 * underlying vectors.
	 */
	
	// the parameter matrices:
	public SimpleMatrix projection_matrix;
	public SimpleMatrix bias_vector;
	public SimpleMatrix model_matrix;
	public SimpleMatrix object_matrix;
	
	public SimpleMatrix norm_model_matrix;
	public SimpleMatrix norm_object_matrix;
	
	// convert words and objects to indices with look-ups
	public ArrayList<String> index2word;
	public Map<String, Integer> word2index;
	
	public ArrayList<String> index2object;
	public Map<String, Integer> object2index;;
	
	// the parameters for the instance of the model
	public Integer window;
	public Integer size;
	public Integer object_size;
	
	// the parameters for the projection space of the model:
	public ArrayList<Integer> output_classes;
	public ArrayList<ArrayList<String>> output_labels;
	public Integer output_sigmoid_classes;
	public ArrayList<String> output_sigmoid_labels;
	public Integer prediction_size;
	
	// special words:
	
	public Integer UnknownWordIndex;
	public Integer UnknownUppercaseWordIndex;
	
	/*
	 * Construct the object Language Model using parameter
	 * matrices.
	 * 
	 * Note: Alternatively you could construct the model
	 * using simply parameters, and initialize the matrices with
	 * noise or zeros.
	 */
	public ObjectLM(
			SimpleMatrix projection_matrix,
			SimpleMatrix bias_vector,
			SimpleMatrix model_matrix,
			SimpleMatrix object_matrix,
			ArrayList<String> index2word,
			Map<String, Integer> word2index,
			int UnknownWordIndex,
			int UnknownUppercaseWordIndex,
			ArrayList<String> index2object,
			Map<String, Integer> object2index,
			int window,
			int size,
			int object_size,
			ArrayList<Integer> output_classes,
			ArrayList<ArrayList<String>> output_labels,
			int output_sigmoid_classes,
			ArrayList<String> output_sigmoid_labels) throws Exception {
		this.projection_matrix = projection_matrix;
		this.bias_vector   = bias_vector;
		
		this.fix_bias_orientation();
		
		this.model_matrix      = model_matrix;
		this.object_matrix     = object_matrix;
		
		this.index2word = index2word;
		this.word2index = word2index;
		
		this.UnknownWordIndex = UnknownWordIndex;
		this.UnknownUppercaseWordIndex = UnknownUppercaseWordIndex;
		
		this.index2object = index2object;
		this.object2index = object2index;
		
		this.window = window;
		this.size = size;
		this.object_size = object_size;
		
		this.output_classes = output_classes;
		this.output_labels = output_labels;
		
		this.output_sigmoid_classes = output_sigmoid_classes;
		this.output_sigmoid_labels = output_sigmoid_labels;
		
		this.prediction_size = output_sigmoid_classes;
		
		// sum up the size of the prediction of
		// softmax and sigmoid:
		for (int i : output_classes) {
			this.prediction_size += i;
		}
		
		create_normalized_matrices();
	}
	
	/*
	 * Reorganizes the bias vector into a column vector
	 * (lots of rows, only 1 column).
	 */
	private void fix_bias_orientation () {
		if (this.bias_vector.numRows() < this.bias_vector.numCols()) {
			this.bias_vector = this.bias_vector.transpose();
		}
	}
	
	public void create_normalized_matrices() throws Exception {
		// sum the squares of each vector, square root each to get norm,
		// and divide each column by the norm as done in the Python
		// implementation.
		this.norm_model_matrix = VectorUtils.element_divide(
				model_matrix,
				VectorUtils.sqrt(
						VectorUtils.sum(
								model_matrix.elementMult(model_matrix),
								-1),
								true),
						false);
		this.norm_object_matrix = VectorUtils.element_divide(
				object_matrix,
				VectorUtils.sqrt(
						VectorUtils.sum(
								object_matrix.elementMult(object_matrix),
								-1),
								true),
						false);
	}
	
	
	/*
	 * Perform lookup for a word's vector using an index.
	 */
	public SimpleMatrix get_word_vector(int index) {
		return this.model_matrix.extractVector(true, index).transpose();
	}
	
	/*
	 * Perform lookup for an object's vector using an index.
	 */
	public SimpleMatrix get_object_vector(int object_index) {
		return this.object_matrix.extractVector(true, object_index).transpose();
	}
	
	/*
	 * Converts indices for words and objects and converts them to an observation
	 * vector by performing look ups in the model_matrix and the object_matrix.
	 */
	public SimpleMatrix observation_vector(List<Integer> indices, int object_index) {

		SimpleMatrix observation = new SimpleMatrix(window * size + object_size, 1);
		
		int index = 0;
		for (int i : indices) {
			observation.insertIntoThis(index, 0, get_word_vector(i));
			index += size;
		}
		observation.insertIntoThis(index, 0, get_object_vector(object_index));
		
		return observation;
	}
	
	/* Takes as input the linear projection and then applies the
	 * sigmoid and softmax nonlinearities to obtain probabilities
	 * for each class (values between 0 and 1), and for softmax,
	 * an alphabet of values that sum to 1.
	 */
	private SimpleMatrix normalize_predictions(SimpleMatrix unnormalized) {
		SimpleMatrix prediction = new SimpleMatrix(prediction_size, 1);
		
		int index = 0;
		// for each softmax class extract a prediction
		for (int output_size : output_classes) {
			prediction.insertIntoThis(
					index,
					0,
					VectorUtils.softmax(
							unnormalized.extractMatrix(
									index,
									index + output_size,
									0,
									1)
									)
									);
			index += output_size;
		}
		
		// for the sigmoid classes sigmoid each:
		prediction.insertIntoThis(index, 0, VectorUtils.element_wise_sigmoid(unnormalized.extractMatrix(
									index,
									index + output_sigmoid_classes,
									0,
									1)
									)
									);
		return prediction;
	}
	/* Projects the observation vector into the prediction space
	 * by multiplying by projection matrix and adding bias vector,
	 * then applying the softmax and sigmoid nonlinearities.
	 */
	public SimpleMatrix project(SimpleMatrix observation) {
		SimpleMatrix unnormalized_predictions = projection_matrix.mult(observation).plus(bias_vector);
		return normalize_predictions(unnormalized_predictions);
	}
	
	/* Take word and object indices and converts them to an observation,
	 * which can then be projected into the prediction space.
	 */
	public SimpleMatrix predict_proba(List<Integer> indices, int object_index) {
		SimpleMatrix observation = observation_vector(indices, object_index);
		return project(observation);
	}
	
	public SimpleMatrix predict_proba(Integer[] indices, int object_index) {
		return predict_proba(new ArrayList<Integer>(Arrays.asList(indices)), object_index);
	}
	
	public ArrayList<Integer> predict(List<Integer> indices, int object_index) {
		SimpleMatrix predictions = predict_proba(indices, object_index);
		ArrayList<Integer> labels = new ArrayList<Integer>();
		
		int index = 0;
		// for each softmax class extract a prediction
		for (int output_size : output_classes) {
			labels.add(VectorUtils.argmax(
					predictions.extractMatrix(
									index,
									index + output_size,
									0,
									1)));
			index += output_size;
		}
		
		for (int i = 0; i < output_sigmoid_classes ; ++i) {
			labels.add((int) Math.round(predictions.get(index + i, 0)));
		}
		
		return labels;	
	}
	
	public ArrayList<Integer> predict(Integer[] indices, int object_index) {
		return predict(new ArrayList<Integer>(Arrays.asList(indices)), object_index);	
	}
	
	public String toString() {
		String self = "<ObjectLM ";
		self += "window = " + window + ", ";
		self += "size = " + size + ", ";
		self += "object_size = " + object_size + ", ";
		self += "output_classes = " + output_classes + ", ";
		self += "output_sigmoid_classes = " + output_sigmoid_classes +", ";
		self += "number_of_parameters = " + number_of_parameters() + ">";
		
		return self;
	}
	
	public SimpleMatrix output_label_representation(int[] output_index) {
		SimpleMatrix vec = new SimpleMatrix(prediction_size, 1);
		for (int c : output_index) {
			vec.set(c, 0, 1.0);
		}
		SimpleMatrix search_vec = projection_matrix.transpose().mult(vec);
		return VectorUtils.normalize(search_vec.extractMatrix(window * size, window * size + object_size, 0, 1));
	}
	
	public SimpleMatrix output_label_representation(List<Tuple<Integer, Double>> output_indices) {
		SimpleMatrix vec = new SimpleMatrix(prediction_size, 1);
		for (Tuple<Integer, Double> c : output_indices) {
			vec.set(c.x, 0, c.y);
		}
		SimpleMatrix search_vec = projection_matrix.transpose().mult(vec);
		return VectorUtils.normalize(search_vec.extractMatrix(window * size, window * size + object_size, 0, 1));
	}
	
	public SimpleMatrix output_label_representation(int output_index) {
		SimpleMatrix vec = new SimpleMatrix(prediction_size, 1);
		vec.set(output_index, 0, 1.0);
		SimpleMatrix search_vec = projection_matrix.transpose().mult(vec);
		return VectorUtils.normalize(search_vec.extractMatrix(window * size, window * size + object_size, 0, 1));
	}
	
	public ArrayList<Triple<Double, String, Integer>> search_object_using_output_labels(int[] output_index, Integer topn) {
		return most_similar_object(output_label_representation(output_index), topn);
	}
	public ArrayList<Triple<Double, String, Integer>> search_object_using_output_labels(List<Tuple<Integer, Double>> output_index, Integer topn) {
		return most_similar_object(output_label_representation(output_index), topn);
	}
	public ArrayList<Triple<Double, String, Integer>> search_object_using_output_labels(int output_index, Integer topn) {
		return most_similar_object(output_label_representation(output_index), topn);
	}
	
	/*
	 * Report the number of total numbers learnt in each matrix for the model.
	 */
	public Integer number_of_parameters() {
		return bias_vector.getNumElements() + projection_matrix.getNumElements() + model_matrix.getNumElements() + object_matrix.getNumElements();
	}
	
	/*
	 * For serialization we use a version id:
	 */
	private static final long serialVersionUID = 1L;
	
	public static ObjectLM load_saved_python_model(String pathname) throws Exception {
		// add folder slash if pathname doesn't have one:
		if (!pathname.endsWith("/")) {
			pathname = pathname + "/";
		}
		
		File f = new File(pathname);
		if (!f.isDirectory()) throw new Exception("Path should point to directory of saved parameters.");
		
		SavedParameterReader.check_file_existence(pathname, "parameters.mat");
		SavedParameterReader.check_file_existence(pathname, "__dict__.txt");
		SavedParameterReader.check_file_existence(pathname, "__vocab__.gz");
		SavedParameterReader.check_file_existence(pathname, "__objects__.gz");
		
		// load the parameter matrices:
		Map<String, SimpleMatrix> m       = MatrixImporter.load_matrix(pathname + "parameters.mat");
		// load the model parameters:
		Map<String, String> params        = SavedParameterReader.convert_file_to_map(pathname + "__dict__.txt");
		// load the vocabulary mapping:
		SavedVocabulary vocab             = SavedParameterReader.load_vocabulary(pathname + "__vocab__.gz");
		SavedVocabulary objects           = SavedParameterReader.load_vocabulary(pathname + "__objects__.gz");
		
		ArrayList<Integer> output_classes = SavedParameterReader.convert_string_to_output_classes(params.get("output_classes"));
		
		return new ObjectLM(
				m.get("projection_matrix"),
				m.get("bias_vector"),
				m.get("model_matrix"),
				m.get("object_matrix"),
				vocab.index2word,
				vocab.word2index,
				Integer.parseInt(params.get("UnknownWordIndex")),
				Integer.parseInt(params.get("UnknownUppercaseWordIndex")),
				objects.index2word,
				objects.word2index,
				Integer.parseInt(params.get("window")),
				Integer.parseInt(params.get("size")),
				Integer.parseInt(params.get("object_size")),
				output_classes,
				SavedParameterReader.convert_map_to_output_labels(params, output_classes.size()),
				Integer.parseInt(params.get("output_sigmoid_classes")),
				SavedParameterReader.convert_string_to_list(params.get("sigmoid_labels")));
	}
	
	public Integer get_index(String word) {
		
		if (word2index.containsKey(word)) {
			return word2index.get(word);
		} else if (Character.isUpperCase(word.charAt(0))) {
			return UnknownUppercaseWordIndex;
		} else {
			return UnknownWordIndex;
		}
		
	}
	
	public ArrayList<Integer> convert_to_indices(String sentence) {
		ArrayList<Integer> indices = new ArrayList<Integer>();
		
		for (String word : sentence.split(" ")) {
			if ( word != "") {
				indices.add(get_index(word));
			}
		}
		return indices;
	}
	
	public ArrayList<Triple<Double, String, Integer>> most_similar_using_matrix(SimpleMatrix prism, int index, List<String> index2word, Integer topn) {
		if (topn == null) {
			topn = 10;
		}
		SimpleMatrix dists = prism.mult( prism.extractVector(true, index).transpose() );
		List<Integer> best = VectorUtils.argsort(dists, false);
		best = best.subList(0, topn + 1);
		
		ArrayList<Triple<Double, String, Integer>> sims = new ArrayList<Triple<Double, String, Integer>>();
		
		for (Integer i : best) {
			if (i != index) {
				sims.add(new Triple<Double, String, Integer>(
						dists.get(i, 0),
						index2word.get(i),
						i
						));
			}
		}
		return sims;
	}
	
	public ArrayList<Triple<Double, String, Integer>> most_similar_using_matrix_vector(SimpleMatrix prism, SimpleMatrix x, List<String> index2word, Integer topn) {
		if (topn == null) {
			topn = 10;
		}
		SimpleMatrix dists = prism.mult( x );
		List<Integer> best = VectorUtils.argsort(dists, false);
		best = best.subList(0, topn);
		
		ArrayList<Triple<Double, String, Integer>> sims = new ArrayList<Triple<Double, String, Integer>>();
		
		for (Integer i : best) {
			sims.add(new Triple<Double, String, Integer>(
						dists.get(i, 0),
						index2word.get(i),
						i
						));
		}
		return sims;
	}
	
	
	
	public ArrayList<Triple<Double, String, Integer>> most_similar_word(String word, Integer topn) {
		return most_similar_using_matrix(norm_model_matrix, get_index(word), index2word, topn);
	}
	
	public ArrayList<Triple<Double, String, Integer>> most_similar_object(String object_id, Integer topn) {
		return most_similar_using_matrix(norm_object_matrix, object2index.get(object_id), index2object, topn);
	}
	
	public ArrayList<Triple<Double, String, Integer>> most_similar_object(SimpleMatrix x, Integer topn) {
		return most_similar_using_matrix_vector(norm_object_matrix, x, index2object, topn);
	}
	
	public int number_of_objects () {
		return this.norm_object_matrix.numRows();
	}
	
	public static void main( String[] args) throws Exception {
		// Load the 4th epoch of the model with window size 10
		// language model size 20, and object language model size 20:
		String base_path = "/Users/jonathanraiman/Documents/Master/research/deep_learning/objectlm/saves/objectlm_window_10_lm_20_objlm_20_4/";
		ObjectLM model = load_saved_python_model(base_path);
		
		
		String search_word = "he";
		String search_id = "The Lemongrass";
		// Some sentences that can be read:
		//String sentence = "On January 24th , Apple Computer will introduce Macintosh . And you 'll see why 1984 won't be like `` 1984 '' .";
		String sentence = "Great wine expensive food and tasty soup with rolls and parsley .";
		
		// the index of the document:
		int object_index = 0;
		
		// Prepare as input:
		ArrayList<Integer> indices = model.convert_to_indices(sentence);
		
		// Project:
		ArrayList<Integer> labels = model.predict(indices.subList(0, model.window), object_index);
		
		// Restitute:
		int num_output_classes = model.output_classes.size();
		System.out.println("Predictions for:");
		System.out.println(" Word window: \"" + sentence + '"');
		System.out.println("Restaurant id: \"" + model.index2object.get(object_index) + '"');
		System.out.println();
		
		System.out.println("Predicted Price & Rating:");
		System.out.println("=========================");
		for (int i = 0; i < num_output_classes; ++i ) {
			System.out.println(model.output_labels.get(i).get(labels.get(i)));
		}
		System.out.println();
		System.out.println("Predicted Categories:");
		System.out.println("=====================");
		
		for (int i = 0; i < model.output_sigmoid_classes; ++i) {
			if (labels.get(num_output_classes + i) == 1) {
				System.out.println(model.output_sigmoid_labels.get(i));
			}
		}
		
		System.out.println("\n\n");
		
		System.out.println("Search");
		System.out.println("======");
		System.out.println("\n\n");
		
		
		// Search for words:
		System.out.println("Neighbors for Word:");
		System.out.println("==========");
		System.out.println('"' + search_word + '"');
		for (Triple<Double, String, Integer> result : model.most_similar_word(search_word, 10)) {
			System.out.println(result.y + " : " + result.x);
		}
		
		System.out.println("\n\n");
		
		// Search for Objects:
		System.out.println("Neighbors for Object:");
		System.out.println("==========");
		System.out.println('"' + search_id + '"');
		for (Triple<Double, String, Integer> result : model.most_similar_object(search_id, 10)) {
			System.out.println(result.y + " : " + result.x);
		}
		
		System.out.println("\n\n");
		
		// Search for Objects:
		System.out.println("Objects near the output label 5, or $$$$:");
		System.out.println("==========");
		for (Triple<Double, String, Integer> result : model.search_object_using_output_labels(4, 10)) {
			System.out.println(result.y + " : " + result.x);
		}
		
		System.out.println("\n\n");
		
		// Search for Objects:
		System.out.println("Objects near the output label 10, or *****:");
		System.out.println("==========");
		for (Triple<Double, String, Integer> result : model.search_object_using_output_labels(9, 10)) {
			System.out.println(result.y + " : " + result.x);
		}
		
		long startTime = System.nanoTime();
		double[] pdists = VectorUtils.compute_pdist(model.norm_object_matrix);
		double[][] Z = HierarchicalCluster.average_link(pdists, model.number_of_objects());
		long endTime = System.nanoTime();

		long duration = (endTime - startTime);
		System.out.println("cluster calculation time : " + (duration / 1000000) + " ms");
		
		/*startTime = System.nanoTime();
		JSONObject output = new JSONObject(root);
		endTime = System.nanoTime();
		duration = (endTime - startTime);
		System.out.println("json building time : " + (duration / 1000000) + " ms");
		
		startTime = System.nanoTime();
		output = HierarchicalCluster.hierarchy_map(model.norm_object_matrix);
		endTime = System.nanoTime();
		duration = (endTime - startTime);
		System.out.println("direct json building time : " + (duration / 1000000) + " ms");
		File f = new File("hierarchy.json");
		output.write(new FileWriter(f));*/
		
		HierarchicalCluster.save_tree_matrix(Z, "hierarchy_map.ser");
		
	}
	
}
