package de.learnlib.passive.rpni;

import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import net.automatalib.words.Alphabet;
import de.learnlib.passive.api.PassiveLearningAlgorithm;
import de.learnlib.passive.commons.bluefringe.DefaultProcessingOrders;
import de.learnlib.passive.commons.bluefringe.ProcessingOrder;
import de.learnlib.passive.commons.pta.BlueFringePTA;
import de.learnlib.passive.commons.pta.BlueFringePTAState;
import de.learnlib.passive.commons.pta.PTATransition;
import de.learnlib.passive.commons.pta.RedBlueMerge;

/**
 * Abstract base class for Blue-Fringe-RPNI algorithms.
 * <p>
 * Unlike most descriptions of RPNI in the literature, the Blue Fringe version
 * of RPNI does not consider all possible pairs of states for merging, but instead
 * maintains a monotonically growing set of "red states", the immediate non-red
 * successors of which are called blue states. In each iteration of the main loop,
 * an attempt is made to merge a blue state into any red state. If this is impossible,
 * the blue state is promoted, meaning it is converted into a red state itself. The
 * procedure terminates when all states are red.
 * <p>
 * A blue fringe version of RPNI is described in the book "Grammatical Inference" by
 * Colin de la Higuera.
 * 
 * @author Malte Isberner
 *
 * @param <I> input symbol type
 * @param <D> output domain type
 * @param <SP> state property type
 * @param <TP> transition property type
 * @param <M> model type
 */
public abstract class AbstractBlueFringeRPNI<I,D,SP,TP,M> implements PassiveLearningAlgorithm<M, I, D> {
	
	protected final Alphabet<I> alphabet;
	protected final int alphabetSize;
	
	@Nonnull
	protected ProcessingOrder order = DefaultProcessingOrders.CANONICAL_ORDER;
	protected boolean parallel = true;
	protected boolean deterministic = false;

	/**
	 * Constructor.
	 * @param alphabet the alphabet
	 */
	public AbstractBlueFringeRPNI(Alphabet<I> alphabet) {
		this.alphabet = alphabet;
		this.alphabetSize = alphabet.size();
	}
	
	/**
	 * Sets whether attempts to merge a blue into a red state are conducted in parallel.
	 * <p>
	 * Note that setting this to {@code true} does not inhibit the possibility of deterministic
	 * algorithm runs (see {@link #setDeterministic(boolean)}).
	 * 
	 * @param parallel whether to parallelize the process of finding a possible merge
	 */
	public void setParallel(boolean parallel) {
		this.parallel = parallel;
	}
	
	/**
	 * Sets whether the outcome of the algorithm is required to be deterministic (i.e., subsequent
	 * calls of {@link #computeModel()} on the same input data will perform the same merges and return
	 * the same result).
	 * <p>
	 * Note that if parallel execution is disabled (see {@link #setParallel(boolean)}), the algorithm
	 * will most likely (but is not required to) behave deterministically even with this set to
	 * {@code false}. However, if parallelization is enabled, results of subsequent invocations will
	 * most likely differ with this parameter set to {@code false}.
	 * 
	 * @param deterministic whether to enforce deterministic algorithm behavior
	 */
	public void setDeterministic(boolean deterministic) {
		this.deterministic = deterministic;
	}
	
	/**
	 * Attempts to merge a blue state into a red state.
	 * 
	 * @param pta the blue fringe PTA
	 * @param qr the red state (i.e., the merge target)
	 * @param qb the blue state (i.e., the merge source)
	 * @return a valid {@link RedBlueMerge} object representing a possible merge of
	 * {@code qb} into {@code qr}, or {@code null} if the merge is impossible
	 */
	protected RedBlueMerge<SP, TP, BlueFringePTAState<SP, TP>>
	tryMerge(
			BlueFringePTA<SP, TP> pta,
			BlueFringePTAState<SP, TP> qr,
			BlueFringePTAState<SP, TP> qb) {
		return pta.tryMerge(qr, qb);
	}

	/*
	 * (non-Javadoc)
	 * @see de.learnlib.passive.api.PassiveLearningAlgorithm#computeModel()
	 */
	@Override
	public M computeModel() {
		BlueFringePTA<SP,TP> pta = new BlueFringePTA<>(alphabetSize);
		initializePTA(pta);
		
		Queue<PTATransition<BlueFringePTAState<SP,TP>>> blue = order.createWorklist();
		
		pta.init(blue::offer);
		
		PTATransition<BlueFringePTAState<SP,TP>> qbRef;
		while ((qbRef = blue.poll()) != null) {
			BlueFringePTAState<SP,TP> qb = qbRef.getTarget();
			
			Stream<BlueFringePTAState<SP,TP>> stream = pta.redStatesStream();
			if (parallel) {
				stream = stream.parallel();
			}
			
			Stream<RedBlueMerge<SP,TP,BlueFringePTAState<SP,TP>>> filtered = stream.map(qr -> tryMerge(pta, qr, qb))
					.filter(Objects::nonNull).filter(this::decideOnValidMerge);
			
			Optional<RedBlueMerge<SP,TP,BlueFringePTAState<SP,TP>>> result = (deterministic) ?
					filtered.findFirst() : filtered.findAny();

			if (result.isPresent()) {
				RedBlueMerge<SP,TP,BlueFringePTAState<SP,TP>> mod = result.get();
				mod.apply(pta, blue::offer);
			}
			else {
				pta.promote(qb, blue::offer);
			}
		}
		
		return ptaToModel(pta);
	}
	
	/**
	 * Initializes an empty PTA with sample data.
	 * @param pta the PTA to initialize
	 */
	protected abstract void initializePTA(BlueFringePTA<SP, TP> pta);
	
	/**
	 * Transforms the final PTA into a model.
	 * @param pta the final PTA
	 * @return a model built from the final PTA
	 */
	protected abstract M ptaToModel(BlueFringePTA<SP, TP> pta);

	/**
	 * Implementing the method allows subclasses to decide (and possible reject) valid merges.
	 *
	 * @param merge the prosed (valid) merge
	 * @return {@code true} if the suggested merge should be performed, {@code false} otherwise
	 */
	protected boolean decideOnValidMerge(RedBlueMerge<SP,TP,BlueFringePTAState<SP,TP>> merge) {
		// by default we are greedy and try to merge the first pair of valid states
		return true;
	}
	
}