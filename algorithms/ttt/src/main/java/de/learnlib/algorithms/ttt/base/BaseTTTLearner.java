/* Copyright (C) 2014 TU Dortmund
 * This file is part of LearnLib, http://www.learnlib.de/.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.learnlib.algorithms.ttt.base;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;

import net.automatalib.automata.fsa.DFA;
import net.automatalib.commons.smartcollections.ElementReference;
import net.automatalib.commons.smartcollections.UnorderedCollection;
import net.automatalib.graphs.dot.EmptyDOTHelper;
import net.automatalib.graphs.dot.GraphDOTHelper;
import net.automatalib.words.Alphabet;
import net.automatalib.words.Word;

import com.google.common.collect.Iterators;

import de.learnlib.acex.AcexAnalyzer;
import de.learnlib.acex.analyzers.AcexAnalyzers;
import de.learnlib.algorithms.ttt.base.TTTHypothesis.TTTEdge;
import de.learnlib.api.LearningAlgorithm;
import de.learnlib.api.MembershipOracle;
import de.learnlib.counterexamples.acex.OutInconsPrefixTransformAcex;
import de.learnlib.oracles.DefaultQuery;

/**
 * The TTT learning algorithm for {@link DFA}.
 * 
 * @author Malte Isberner
 *
 * @param <I> input symbol type
 */
public abstract class BaseTTTLearner<A,I,D> implements LearningAlgorithm<A,I,D> {
	
	public static class BuilderDefaults {
		public static AcexAnalyzer analyzer() {
			return AcexAnalyzers.BINARY_SEARCH_BWD;
		}
	}
	
	protected final Alphabet<I> alphabet;
	protected final TTTHypothesis<I,D,?> hypothesis;
	protected final MembershipOracle<I, D> oracle;
	
	protected final DiscriminationTree<I,D> dtree;
	
	protected final AcexAnalyzer analyzer;
	
	private final Collection<TTTEventListener<I, D>> eventListeners = new UnorderedCollection<>();
	
	/**
	 * Open transitions, i.e., transitions that possibly point to a non-leaf
	 * node in the discrimination tree.
	 */
	protected final IncomingList<I, D> openTransitions = new IncomingList<>();
	
	/**
	 * The blocks during a split operation. A block is a maximal subtree of the
	 * discrimination tree containing temporary discriminators at its root. 
	 */
	protected final BlockList<I,D> blockList = new BlockList<>();
	
	protected BaseTTTLearner(Alphabet<I> alphabet, MembershipOracle<I, D> oracle,
			TTTHypothesis<I, D, ?> hypothesis,
			AcexAnalyzer analyzer) {
		this.alphabet = alphabet;
		this.hypothesis = hypothesis;
		this.oracle = oracle;
		this.dtree = new DiscriminationTree<>(oracle);
		this.analyzer = analyzer;
	}
	
	protected BaseTTTLearner(Alphabet<I> alphabet, MembershipOracle<I, D> oracle,
			TTTHypothesis<I, D, ?> hypothesis,
			AcexAnalyzer analyzer,
			DTNode<I,D> root) {
		this.alphabet = alphabet;
		this.hypothesis = hypothesis;
		this.oracle = oracle;
		this.dtree = new DiscriminationTree<>(oracle, root);
		this.analyzer = analyzer;
	}
	
	
	/*
	 * LearningAlgorithm interface methods
	 */

	/*
	 * (non-Javadoc)
	 * @see de.learnlib.api.LearningAlgorithm#startLearning()
	 */
	@Override
	public void startLearning() {
		if(hypothesis.isInitialized()) {
			throw new IllegalStateException();
		}
		
		TTTState<I, D> init = hypothesis.initialize();
		DTNode<I, D> initNode = dtree.sift(init, false);
		link(initNode, init);
		initializeState(init);
		
		closeTransitions();
	}

	/*
	 * (non-Javadoc)
	 * @see de.learnlib.api.LearningAlgorithm#refineHypothesis(de.learnlib.oracles.DefaultQuery)
	 */
	@Override
	public boolean refineHypothesis(DefaultQuery<I, D> ceQuery) {
		if(!refineHypothesisSingle(ceQuery)) {
			return false;
		}
		
		DefaultQuery<I, D> currCe = ceQuery;
		while(refineHypothesisSingle(currCe));
		
		return true;
	}
	
	
	
	
	/*
	 * Private helper methods.
	 */
	
	
	/**
	 * Initializes a state. Creates its outgoing transition objects, and adds them
	 * to the "open" list.
	 * @param state the state to initialize
	 */
	protected void initializeState(TTTState<I,D> state) {
		for(int i = 0; i < alphabet.size(); i++) {
			I sym = alphabet.getSymbol(i);
			TTTTransition<I,D> trans = createTransition(state, sym);
			trans.setNonTreeTarget(dtree.getRoot());
			state.transitions[i] = trans;
			openTransitions.insertIncoming(trans);
		}
	}
	
	protected TTTTransition<I,D> createTransition(TTTState<I,D> state, I sym) {
		return new TTTTransition<I, D>(state, sym);
	}
	
	
	/**
	 * Performs a single refinement of the hypothesis, i.e., without 
	 * repeated counterexample evaluation. The parameter and return value
	 * have the same significance as in {@link #refineHypothesis(DefaultQuery)}.
	 * 
	 * @param ceQuery the counterexample (query) to be used for refinement
	 * @return {@code true} if the hypothesis was refined, {@code false} otherwise
	 */
	protected boolean refineHypothesisSingle(DefaultQuery<I, D> ceQuery) {
		TTTState<I,D> state = getAnyState(ceQuery.getPrefix());
		D out = computeHypothesisOutput(state, ceQuery.getSuffix());
		
		if(Objects.equals(out, ceQuery.getOutput())) {
			return false;
		}
		
		OutputInconsistency<I,D> outIncons = new OutputInconsistency<I,D>(
				state,
				ceQuery.getSuffix(),
				ceQuery.getOutput());
		
		do {
			splitState(outIncons);
			closeTransitions();
			while (finalizeAny()) {
				closeTransitions();
			}
			
			outIncons = findOutputInconsistency();
		} while(outIncons != null);
		assert allNodesFinal();
		
		return true;
	}
	
	private void splitState(OutputInconsistency<I, D> outIncons) {
		OutInconsPrefixTransformAcex<I, D> acex = deriveAcex(outIncons);
		int breakpoint = analyzer.analyzeAbstractCounterexample(acex);
		assert !acex.testEffects(breakpoint, breakpoint+1);
		
		Word<I> suffix = outIncons.suffix;
		
		TTTState<I,D> predState = getDeterministicState(outIncons.srcState, suffix.prefix(breakpoint));
		TTTState<I,D> succState = getDeterministicState(outIncons.srcState, suffix.prefix(breakpoint + 1));
		assert getDeterministicState(predState, Word.fromLetter(suffix.getSymbol(breakpoint))) == succState;
		
		I sym = suffix.getSymbol(breakpoint);
		Word<I> splitSuffix = suffix.subWord(breakpoint + 1);
		TTTTransition<I, D> trans = predState.transitions[alphabet.getSymbolIndex(sym)];
		D oldOut = acex.effect(breakpoint + 1);
		D newOut = succEffect(acex.effect(breakpoint));
		
		splitState(trans, splitSuffix, oldOut, newOut);
	}
	
	protected OutInconsPrefixTransformAcex<I, D> deriveAcex(OutputInconsistency<I, D> outIncons) {
		TTTState<I, D> source = outIncons.srcState;
		Word<I> suffix = outIncons.suffix;
		
		OutInconsPrefixTransformAcex<I,D> acex = new OutInconsPrefixTransformAcex<>(suffix, oracle,
				w -> getDeterministicState(source, w).getAccessSequence());
		
		acex.setEffect(0, outIncons.targetOut);
		return acex;
	}
	
	protected abstract D succEffect(D effect);
	
	
	/**
	 * Chooses a block root, and finalizes the corresponding discriminator.
	 * @return {@code true} if a splittable block root was found, {@code false}
	 * otherwise.
	 */
	protected boolean finalizeAny() {
		GlobalSplitter<I,D> splitter = findSplitterGlobal();
		if(splitter != null) {
			finalizeDiscriminator(splitter.blockRoot, splitter.localSplitter);
			return true;
		}
		return false;
	}
	
	protected TTTState<I,D> getDeterministicState(TTTState<I,D> start, Word<I> word) {
		TTTState<I,D> lastSingleton = start;
		int lastSingletonIndex = 0;
		
		Set<TTTState<I,D>> states = Collections.singleton(start);
		int i = 1;
		for (I sym : word) {
			Set<TTTState<I,D>> nextStates = getNondetSuccessors(states, sym);
			if (nextStates.size() == 1) {
				lastSingleton = nextStates.iterator().next();
				lastSingletonIndex = i;
			}
			states = nextStates;
			
			i++;
		}
		if (lastSingletonIndex == word.length()) {
			return lastSingleton;
		}
		
		TTTState<I,D> curr = lastSingleton;
		for (I sym : word.subWord(lastSingletonIndex)) {
			TTTTransition<I, D> trans = curr.transitions[alphabet.getSymbolIndex(sym)];
			TTTState<I,D> next = requireSuccessor(trans);
			curr = next;
		}
		
		return curr;
	}
	
	protected Set<TTTState<I,D>> getNondetSuccessors(Collection<? extends TTTState<I,D>> states, I sym) {
		Set<TTTState<I,D>> result = new HashSet<>();
		int symIdx = alphabet.getSymbolIndex(sym);
		for (TTTState<I,D> state : states) {
			TTTTransition<I, D> trans = state.transitions[symIdx];
			if (trans.isTree()) {
				result.add(trans.getTreeTarget());
			}
			else {
				DTNode<I,D> tgtNode = trans.getNonTreeTarget();
				Iterators.addAll(result, tgtNode.subtreeStatesIterator());
			}
		}
		return result;
	}
	
	protected Collection<? extends TTTState<I,D>> getNondetSuccessors(Collection<? extends TTTState<I,D>> states,
			Iterable<? extends I> suffix) {
		Collection<? extends TTTState<I,D>> curr = states;
		for (I sym : suffix) {
			curr = getNondetSuccessors(curr, sym);
		}
		return curr;
	}
	
	protected TTTState<I,D> getAnySuccessor(TTTState<I,D> state, I sym) {
		int symIdx = alphabet.getSymbolIndex(sym);
		TTTTransition<I, D> trans = state.transitions[symIdx];
		if (trans.isTree()) {
			return trans.getTreeTarget();
		}
		return trans.getNonTreeTarget().subtreeStatesIterator().next();
	}
	
	protected TTTState<I,D> getAnySuccessor(TTTState<I,D> state, Iterable<? extends I> suffix) {
		TTTState<I,D> curr = state;
		for (I sym : suffix) {
			curr = getAnySuccessor(curr, sym);
		}
		return curr;
	}
	
	protected TTTTransition<I,D> getStateTransition(TTTState<I,D> state, I sym) {
		int idx = alphabet.getSymbolIndex(sym);
		return state.transitions[idx];
	}
	
	private TTTState<I,D> requireSuccessor(TTTTransition<I, D> trans) {
		if (trans.isTree()) {
			return trans.getTreeTarget();
		}
		DTNode<I, D> newTgtNode = updateDTTarget(trans, true);
		if (newTgtNode.state == null) {
			makeTree(trans);
			closeTransitions();
		}
		return newTgtNode.state;
	}
	
	
	
	/**
	 * Data structure for representing a splitter.
	 * <p>
	 * A splitter is represented by an input symbol, and a DT node
	 * that separates the successors (wrt. the input symbol) of the original
	 * states. From this, a discriminator can be obtained by prepending the input
	 * symbol to the discriminator that labels the separating successor.
	 * <p>
	 * <b>Note:</b> as the discriminator finalization is applied to the root
	 * of a block and affects all nodes, there is no need to store references
	 * to the source states from which this splitter was obtained.
	 * 
	 * @author Malte Isberner
	 *
	 * @param <I> input symbol type
	 */
	public static final class Splitter<I,D> {
		public final int symbolIdx;
		public final DTNode<I,D> succSeparator;
		
		public Splitter(int symbolIdx) {
			this.symbolIdx = symbolIdx;
			this.succSeparator = null;
		}
		
		public Splitter(int symbolIdx, DTNode<I,D> succSeparator) {
			assert !succSeparator.isTemp() && succSeparator.isInner();
			
			this.symbolIdx = symbolIdx;
			this.succSeparator = succSeparator;
		}
		
		public Word<I> getDiscriminator() {
			return (succSeparator != null) ? succSeparator.getDiscriminator() : Word.epsilon();
		}
		
		public int getDiscriminatorLength() {
			return (succSeparator != null) ? succSeparator.getDiscriminator().length() : 0;
		}
	}
	
	/**
	 * A global splitter. In addition to the information stored in a (local)
	 * {@link Splitter}, this class also stores the block the local splitter
	 * applies to.
	 * 
	 * @author Malte Isberner
	 *
	 * @param <I> input symbol type
	 */
	private static final class GlobalSplitter<I,D> {
		public final Splitter<I,D> localSplitter;
		public final DTNode<I,D> blockRoot;
		
		public GlobalSplitter(DTNode<I,D> blockRoot, Splitter<I,D> localSplitter) {
			this.blockRoot = blockRoot;
			this.localSplitter = localSplitter;
		}
	}
	
	/**
	 * Determines a global splitter, i.e., a splitter for any block.
	 * This method may (but is not required to) employ heuristics
	 * to obtain a splitter with a relatively short suffix length.
	 * 
	 * @return a splitter for any of the blocks
	 */
	private GlobalSplitter<I,D> findSplitterGlobal() {
		// TODO: Make global option
		boolean optimizeGlobal = true;
		
		DTNode<I,D> bestBlockRoot = null;
		
		Splitter<I,D> bestSplitter = null;
		
		for (DTNode<I,D> blockRoot : blockList) {
//			if (finalDiscriminators.contains(blockRoot.getDiscriminator().subWord(1))) {
//				declareFinal(blockRoot);
//				continue;
//			}
			Splitter<I,D> splitter = findSplitter(blockRoot);
			
			if(splitter != null) {
				if(bestSplitter == null || splitter.getDiscriminatorLength()
						< bestSplitter.getDiscriminatorLength()) {
					bestSplitter = splitter;
					bestBlockRoot = blockRoot;
				}
				
				if(!optimizeGlobal) {
					break;
				}
			}
		}
		
		if(bestSplitter == null) {
			return null;
		}
		
		return new GlobalSplitter<>(bestBlockRoot, bestSplitter);
	}
	
	/**
	 * Determines a (local) splitter for a given block. This method may
	 * (but is not required to) employ heuristics to obtain a splitter
	 * with a relatively short suffix.
	 *  
	 * @param blockRoot the root of the block
	 * @return a splitter for this block, or {@code null} if no such splitter
	 * could be found.
	 */
	@SuppressWarnings("unchecked")
	private Splitter<I,D> findSplitter(DTNode<I,D> blockRoot) {
		int alphabetSize = alphabet.size();
		
		Object[] properties = new Object[alphabetSize];
		DTNode<I,D>[] lcas = new DTNode[alphabetSize];
		boolean first = true;
		
		for (TTTState<I,D> state : blockRoot.subtreeStates()) {
			for (int i = 0; i < alphabetSize; i++) {
				TTTTransition<I, D> trans = state.transitions[i];
				if (first) {
					properties[i] = trans.getProperty();
					lcas[i] = trans.getDTTarget();
				}
				else {
					if (!Objects.equals(properties[i], trans.getProperty())) {
						return new Splitter<>(i);
					}
					lcas[i] = dtree.leastCommonAncestor(lcas[i], trans.getDTTarget());
				}
			}
			first = false;
		}
		
		int shortestLen = Integer.MAX_VALUE;
		DTNode<I, D> shortestLca = null;
		int shortestLcaSym = -1;
		
		for (int i = 0; i < alphabetSize; i++) {
			DTNode<I,D> lca = lcas[i];
			if (!lca.isTemp() && !lca.isLeaf()) {
				int lcaLen = lca.getDiscriminator().length();
				if (shortestLca == null || lcaLen < shortestLen) {
					shortestLca = lca;
					shortestLen = lcaLen;
					shortestLcaSym = i;
				}
			}
		}
		
		if (shortestLca != null) {
			return new Splitter<>(shortestLcaSym, shortestLca);
		}
		return null;
	}
	
	/**
	 * Creates a state in the hypothesis. This method cannot be used for the initial
	 * state, which has no incoming tree transition.
	 * 
	 * @param transition the "parent" transition in the spanning tree
	 * @param accepting whether or not the new state state is accepting
	 * @return the newly created state
	 */
	private TTTState<I,D> createState(@Nonnull TTTTransition<I,D> transition) {
		TTTState<I,D> newState = hypothesis.createState(transition);
		
		return newState;
	}
	
	
	/**
	 * Retrieves the target state of a given transition. This method works for both tree
	 * and non-tree transitions. If a non-tree transition points to a non-leaf node,
	 * it is updated accordingly before a result is obtained.
	 * 
	 * @param trans the transition
	 * @return the target state of this transition (possibly after it having been updated)
	 */
	protected TTTState<I,D> getAnyTarget(TTTTransition<I,D> trans) {
		if(trans.isTree()) {
			return trans.getTreeTarget();
		}
		return trans.getNonTreeTarget().subtreeStates().iterator().next();
	}
	
	
	/**
	 * Retrieves the state reached by the given sequence of symbols, starting
	 * from the initial state.
	 * @param suffix the sequence of symbols to process
	 * @return the state reached after processing the specified symbols
	 */
	private TTTState<I,D> getAnyState(Iterable<? extends I> suffix) {
		return getAnySuccessor(hypothesis.getInitialState(), suffix);
	}
	
	private OutputInconsistency<I, D> findOutputInconsistency() {
		OutputInconsistency<I, D> best = null;
		
		for (TTTState<I, D> state : hypothesis.getStates()) {
			DTNode<I, D> node = state.getDTLeaf();
			while (!node.isRoot()) {
				D expectedOut = node.getParentEdgeLabel();
				node = node.getParent();
				Word<I> suffix = node.getDiscriminator();
				if (best == null || suffix.length() < best.suffix.length()) {
					D hypOut = computeHypothesisOutput(state, suffix);
					if (!Objects.equals(hypOut, expectedOut)) {
						best = new OutputInconsistency<>(state, suffix, expectedOut);
					}
				}
			}
		}
		return best;
	}
	
	/**
	 * Finalize a discriminator. Given a block root and a {@link Splitter},
	 * replace the discriminator at the block root by the one derived from the
	 * splitter, and update the discrimination tree accordingly.
	 * 
	 * @param blockRoot the block root whose discriminator to finalize
	 * @param splitter the splitter to use for finalization
	 */
	private void finalizeDiscriminator(DTNode<I,D> blockRoot, Splitter<I,D> splitter) {
		assert blockRoot.isBlockRoot();
		
		notifyPreFinalizeDiscriminator(blockRoot, splitter);
		
		Word<I> succDiscr = splitter.getDiscriminator().prepend(alphabet.getSymbol(splitter.symbolIdx));
		
		if (!blockRoot.getDiscriminator().equals(succDiscr)) {
			Word<I> finalDiscriminator = prepareSplit(blockRoot, splitter);
			Map<D,DTNode<I,D>> repChildren = createMap();
			for (D label : blockRoot.splitData.getLabels()) {
				repChildren.put(label, extractSubtree(blockRoot, label));
			}
			blockRoot.replaceChildren(repChildren);

			blockRoot.setDiscriminator(finalDiscriminator);
		}

		declareFinal(blockRoot);
		
		notifyPostFinalizeDiscriminator(blockRoot, splitter);
	}
	
	protected boolean allNodesFinal() {
		Iterator<? extends DTNode<I,D>> it = dtree.getRoot().subtreeNodesIterator();
		while (it.hasNext()) {
			DTNode<I,D> node = it.next();
			assert !node.isTemp() : "Final node with discriminator " + node.getDiscriminator();
		}
		return true;
	}
	protected void declareFinal(DTNode<I,D> blockRoot) {
		blockRoot.temp = false;
		blockRoot.splitData = null;
		
		blockRoot.removeFromBlockList();
//		finalDiscriminators.add(blockRoot.getDiscriminator());
		
		for (DTNode<I,D> subtree : blockRoot.getChildren()) {
			assert subtree.splitData == null;
			blockRoot.setChild(subtree.getParentEdgeLabel(), subtree);
			// Register as blocks, if they are non-trivial subtrees
			if (subtree.isInner()) {
				blockList.insertBlock(subtree);
			}
		}
		openTransitions.insertAllIncoming(blockRoot.getIncoming());
	}
	
	/**
	 * Prepare a split operation on a block, by marking all the nodes and
	 * transitions in the subtree (and annotating them with
	 * {@link SplitData} objects).
	 * 
	 * @param node the block root to be split
	 * @param splitter the splitter to use for splitting the block
	 * @return the discriminator to use for splitting
	 */
	private Word<I> prepareSplit(DTNode<I,D> node, Splitter<I,D> splitter) {
		int symbolIdx = splitter.symbolIdx;
		I symbol = alphabet.getSymbol(symbolIdx);
		Word<I> discriminator = splitter.getDiscriminator().prepend(symbol);
		
		Deque<DTNode<I,D>> dfsStack = new ArrayDeque<>();
		
		DTNode<I,D> succSeparator = splitter.succSeparator;
		
		
		dfsStack.push(node);
		assert node.splitData == null;
		
		while(!dfsStack.isEmpty()) {
			DTNode<I,D> curr = dfsStack.pop();
			assert curr.splitData == null;
			
			curr.splitData = new SplitData<>();
			
			
			for(TTTTransition<I,D> trans : curr.getIncoming()) {
				D outcome = query(trans, discriminator);
				curr.splitData.getIncoming(outcome).insertIncoming(trans);
				markAndPropagate(curr, outcome);
			}
			
			if(curr.isInner()) {
				for (DTNode<I,D> child : curr.getChildren()) {
					dfsStack.push(child);
				}
			}
			else {
				TTTState<I,D> state = curr.state;
				assert state != null;
				
				TTTTransition<I,D> trans = state.transitions[symbolIdx];
				D outcome = predictSuccOutcome(trans, succSeparator);
				assert outcome != null;
				curr.splitData.setStateLabel(outcome);
				markAndPropagate(curr, outcome);
			}
			
		}
		
		return discriminator;
	}
	
	protected abstract D predictSuccOutcome(TTTTransition<I, D> trans, DTNode<I, D> succSeparator);
	
	/**
	 * Marks a node, and propagates the label up to all nodes on the path from the block
	 * root to this node.
	 * 
	 * @param node the node to mark
	 * @param label the label to mark the node with
	 */
	private static <I,D> void markAndPropagate(DTNode<I,D> node, D label) {
		DTNode<I,D> curr = node;
		
		while(curr != null && curr.splitData != null) {
			if(!curr.splitData.mark(label)) {
				return;
			}
			curr = curr.getParent();
		}
	}
	
	/**
	 * Data structure required during an extract operation. The latter basically
	 * works by copying nodes that are required in the extracted subtree, and this
	 * data structure is required to associate original nodes with their extracted copies.
	 *  
	 * @author Malte Isberner
	 *
	 * @param <I> input symbol type
	 */
	private static final class ExtractRecord<I,D> {
		public final DTNode<I,D> original;
		public final DTNode<I,D> extracted;
		
		public ExtractRecord(DTNode<I,D> original, DTNode<I,D> extracted) {
			this.original = original;
			this.extracted = extracted;
		}
	}
	
	/**
	 * Extract a (reduced) subtree containing all nodes with the given label
	 * from the subtree given by its root. "Reduced" here refers to the fact that
	 * the resulting subtree will contain no inner nodes with only one child.
	 * <p>
	 * The tree returned by this method (represented by its root) will have
	 * as a parent node the root that was passed to this method.
	 *  
	 * @param root the root of the subtree from which to extract
	 * @param label the label of the nodes to extract
	 * @return the extracted subtree
	 */
	private DTNode<I,D> extractSubtree(DTNode<I,D> root, D label) {
		assert root.splitData != null;
		assert root.splitData.isMarked(label);
		
		Deque<ExtractRecord<I,D>> stack = new ArrayDeque<>();
		
		DTNode<I,D> firstExtracted = new DTNode<>(root, label);
		
		stack.push(new ExtractRecord<>(root, firstExtracted));
		while(!stack.isEmpty()) {
			ExtractRecord<I,D> curr = stack.pop();
			
			DTNode<I,D> original = curr.original;
			DTNode<I,D> extracted = curr.extracted;
			
			moveIncoming(extracted, original, label);
			
			if(original.isLeaf()) {
				if(Objects.equals(original.splitData.getStateLabel(), label)) {
					link(extracted, original.state);
				}
				else {
					createNewState(extracted);
				}
				extracted.updateIncoming();
			}
			else {
				List<DTNode<I,D>> markedChildren = new ArrayList<>();
				
				for (DTNode<I,D> child : original.getChildren()) {
					if (child.splitData.isMarked(label)) {
						markedChildren.add(child);
					}
				}
				
				if (markedChildren.size() > 1) {
					Map<D,DTNode<I,D>> childMap = createMap();
					for (DTNode<I,D> c : markedChildren) {
						D childLabel = c.getParentEdgeLabel();
						DTNode<I,D> extractedChild = new DTNode<>(extracted, childLabel);
						childMap.put(childLabel, extractedChild);
						stack.push(new ExtractRecord<>(c, extractedChild));
					}
					extracted.split(original.getDiscriminator(), childMap);
					extracted.updateIncoming();
					extracted.temp = true;
				}
				else if (markedChildren.size() == 1) {
					stack.push(new ExtractRecord<>(markedChildren.get(0), extracted));
				}
				else { // markedChildren.isEmppty()
					createNewState(extracted);
					extracted.updateIncoming();
				}
			}	
			
			assert extracted.splitData == null;
		}
		
		return firstExtracted;
	}
	
	protected <V> Map<D,V> createMap() {
		return new HashMap<D,V>();
	}
	
	/**
	 * Moves all transition from the "incoming" list (for a given label) of an
	 * old node to the "incoming" list of a new node.
	 *   
	 * @param newNode the new node
	 * @param oldNode the old node
	 * @param label the label to consider
	 */
	private static <I,D> void moveIncoming(DTNode<I,D> newNode, DTNode<I,D> oldNode, D label) {
		newNode.getIncoming().insertAllIncoming(oldNode.splitData.getIncoming(label));
	}
	
	/**
	 * Create a new state during extraction on-the-fly. This is required if a node
	 * in the DT has an incoming transition with a certain label, but in its subtree
	 * there are no leaves with this label as their state label.
	 * 
	 * @param newNode the extracted node
	 */
	private void createNewState(DTNode<I,D> newNode) {
		TTTTransition<I,D> newTreeTrans = newNode.getIncoming().choose();
		assert newTreeTrans != null;
		
		TTTState<I,D> newState = createState(newTreeTrans);
		link(newNode, newState);
		initializeState(newState);
	}
	
	protected abstract D computeHypothesisOutput(TTTState<I,D> state, Iterable<? extends I> suffix);
	
	/**
	 * Establish the connection between a node in the discrimination tree
	 * and a state of the hypothesis.
	 * 
	 * @param dtNode the node in the discrimination tree
	 * @param state the state in the hypothesis
	 */
	protected static <I,D> void link(DTNode<I,D> dtNode, TTTState<I,D> state) {
		assert dtNode.isLeaf();
		
		dtNode.state = state;
		state.dtLeaf = dtNode;
	}
	
	public TTTHypothesis<I, D, ?> getHypothesisDS() {
		return hypothesis;
	}
	
	public DiscriminationTree<I, D>.GraphView dtGraphView() {
		return dtree.graphView();
	}
	
	public GraphDOTHelper<TTTState<I,D>, TTTEdge<I, D>> getHypothesisDOTHelper() {
		return new EmptyDOTHelper<>();
	}
	
	/**
	 * Splits a state in the hypothesis, using a temporary discriminator. The state
	 * to be split is identified by an incoming non-tree transition. This transition is
	 * subsequently turned into a spanning tree transition.
	 * 
	 * @param transition the transition
	 * @param tempDiscriminator the temporary discriminator
	 * @return the discrimination tree node separating the old and the new node, labeled
	 * by the specified temporary discriminator
	 */
	private DTNode<I,D> splitState(TTTTransition<I,D> transition, Word<I> tempDiscriminator,
			D oldOut, D newOut) {
		assert !transition.isTree();
		
		notifyPreSplit(transition, tempDiscriminator);
		
		DTNode<I,D> dtNode = transition.getNonTreeTarget();
		assert dtNode.isLeaf();
		TTTState<I,D> oldState = dtNode.state;
		assert oldState != null;
		
		TTTState<I,D> newState = makeTree(transition);
		
		DTNode<I,D>[] children = split(dtNode, tempDiscriminator, oldOut, newOut);
		dtNode.temp = true;
		
		link(children[0], oldState);
		link(children[1], newState);
		
		if(dtNode.getParent() == null || !dtNode.getParent().isTemp()) {
			blockList.insertBlock(dtNode);
		}
		
		notifyPostSplit(transition, tempDiscriminator);
		
		return dtNode;
	}
	

	
	protected void closeTransitions() {
		TTTTransition<I, D> next;
		UnorderedCollection<DTNode<I, D>> newStateNodes = new UnorderedCollection<>();
		
		do {
			while ((next = openTransitions.poll()) != null) {
				DTNode<I,D> newStateNode = closeTransition(next, false);
				if (newStateNode != null) {
					newStateNodes.add(newStateNode);
				}
			}
			if (!newStateNodes.isEmpty()) {
				addNewStates(newStateNodes);
			}
		} while(!openTransitions.isEmpty());
	}
	
	private void addNewStates(UnorderedCollection<DTNode<I,D>> newStateNodes) {
		DTNode<I,D> minTransNode = null;
		TTTTransition<I, D> minTrans = null;
		int minAsLen = Integer.MAX_VALUE;
		ElementReference minTransNodeRef = null;
		for (ElementReference ref : newStateNodes.references()) {
			DTNode<I, D> newStateNode = newStateNodes.get(ref);
			for (TTTTransition<I, D> trans : newStateNode.getIncoming()) {
				Word<I> as = trans.getAccessSequence();
				int asLen = as.length();
				if (asLen < minAsLen) {
					minTransNode = newStateNode;
					minTrans = trans;
					minAsLen = asLen;
					minTransNodeRef = ref;
				}
			}
		}
		
		assert minTransNode != null;
		newStateNodes.remove(minTransNodeRef);
		TTTState<I,D> state = makeTree(minTrans);
		link(minTransNode, state);
		initializeState(state);
	}
	
	protected TTTState<I,D> makeTree(TTTTransition<I, D> trans) {
		assert !trans.isTree();
		DTNode<I,D> node = trans.nonTreeTarget;
		assert node.isLeaf();
		TTTState<I,D> state = createState(trans);
		trans.removeFromList();
		link(node, state);
		initializeState(state);
		return state;
	}
	
	/**
	 * Ensures that the specified transition points to a leaf-node. If the transition
	 * is a tree transition, this method has no effect.
	 * 
	 * @param trans the transition
	 */
	private DTNode<I,D> closeTransition(TTTTransition<I,D> trans, boolean hard) {
		if(trans.isTree()) {
			return null;
		}
		
		DTNode<I,D> node = updateDTTarget(trans, hard);
		if (node.isLeaf() && node.state == null && trans.nextIncoming == null) {
			return node;
		}
		return null;
	}
	
	/**
	 * Updates the transition to point to either a leaf in the discrimination tree,
	 * or---if the {@code hard} parameter is set to {@code false}---to a block
	 * root.
	 * 
	 * @param transition the transition
	 * @param hard whether to consider leaves as sufficient targets only
	 * @return the new target node of the transition
	 */
	private DTNode<I,D> updateDTTarget(TTTTransition<I,D> transition, boolean hard) {
		if(transition.isTree()) {
			return transition.getTreeTarget().dtLeaf;
		}
		
		DTNode<I,D> dt = transition.getNonTreeTarget();
		dt = dtree.sift(dt, transition, hard);
		transition.setNonTreeTarget(dt);
		
		return dt;
	}
	
	
	/**
	 * Performs a membership query.
	 * 
	 * @param prefix the prefix part of the query
	 * @param suffix the suffix part of the query
	 * @return the output
	 */
	protected D query(Word<I> prefix, Word<I> suffix) {
		return oracle.answerQuery(prefix, suffix);
	}
	
	/**
	 * Performs a membership query, using an access sequence as its prefix.
	 * 
	 * @param accessSeqProvider the object from which to obtain the access sequence
	 * @param suffix the suffix part of the query
	 * @return the output
	 */
	protected D query(AccessSequenceProvider<I> accessSeqProvider, Word<I> suffix) {
		return query(accessSeqProvider.getAccessSequence(), suffix);
	}
	
	/**
	 * Returns the discrimination tree.
	 * @return the discrimination tree
	 */
	public DiscriminationTree<I,D> getDiscriminationTree() {
		return dtree;
	}

	@SafeVarargs
	protected final DTNode<I,D>[] split(DTNode<I, D> node, Word<I> discriminator, D... outputs) {
		return node.split(discriminator, this.<DTNode<I,D>>createMap(), outputs);
	}
	
	
	private void notifyPreFinalizeDiscriminator(DTNode<I, D> blockRoot, Splitter<I,D> splitter) {
		for (TTTEventListener<I, D> listener : eventListeners()) {
			listener.preFinalizeDiscriminator(blockRoot, splitter);
		}
	}
	
	private void notifyPostFinalizeDiscriminator(DTNode<I, D> blockRoot, Splitter<I,D> splitter) {
		for (TTTEventListener<I, D> listener : eventListeners()) {
			listener.postFinalizeDiscriminator(blockRoot, splitter);
		}
	}
	
	private void notifyPreSplit(TTTTransition<I, D> transition, Word<I> tempDiscriminator) {
		for (TTTEventListener<I, D> listener : eventListeners()) {
			listener.preSplit(transition, tempDiscriminator);
		}
	}
	
	private void notifyPostSplit(TTTTransition<I, D> transition, Word<I> tempDiscriminator) {
		for (TTTEventListener<I, D> listener : eventListeners()) {
			listener.postSplit(transition, tempDiscriminator);
		}
	}
	
	private Iterable<TTTEventListener<I, D>> eventListeners() {
		return eventListeners;
	}
	
	public void addEventListener(TTTEventListener<I, D> listener) {
		eventListeners.add(listener);
	}
	
	public void removeEventListener(TTTEventListener<I, D> listener) {
		eventListeners.remove(listener);
	}
	
}