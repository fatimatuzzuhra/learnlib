/* Copyright (C) 2013 TU Dortmund
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
package de.learnlib.algorithms.lstargeneric;

import net.automatalib.automata.UniversalDeterministicAutomaton;
import net.automatalib.words.Alphabet;

import org.testng.Assert;

import de.learnlib.algorithms.lstargeneric.ce.ObservationTableCEXHandler;
import de.learnlib.algorithms.lstargeneric.ce.ObservationTableCEXHandlers;
import de.learnlib.algorithms.lstargeneric.closing.ClosingStrategies;
import de.learnlib.algorithms.lstargeneric.closing.ClosingStrategy;
import de.learnlib.api.EquivalenceOracle;
import de.learnlib.api.LearningAlgorithm;
import de.learnlib.api.MembershipOracle;
import de.learnlib.oracles.DefaultQuery;

public class LearningTest {
	
	@SuppressWarnings("unchecked")
	public static ClosingStrategy<Object,Object>[] CLOSING_STRATEGIES
		= new ClosingStrategy[]{
		ClosingStrategies.CLOSE_FIRST,
		ClosingStrategies.CLOSE_SHORTEST,
		ClosingStrategies.CLOSE_LEX_MIN,
		ClosingStrategies.CLOSE_RANDOM
	};
	
	@SuppressWarnings("unchecked")
	public static ObservationTableCEXHandler<Object,Object>[] CEX_HANDLERS
		= new ObservationTableCEXHandler[]{
		ObservationTableCEXHandlers.CLASSIC_LSTAR,
		ObservationTableCEXHandlers.SUFFIX1BY1,
		ObservationTableCEXHandlers.MALER_PNUELI,
		ObservationTableCEXHandlers.SHAHBAZ,
		ObservationTableCEXHandlers.FIND_LINEAR,
		ObservationTableCEXHandlers.FIND_LINEAR_ALLSUFFIXES,
		ObservationTableCEXHandlers.FIND_LINEAR_REVERSE,
		ObservationTableCEXHandlers.FIND_LINEAR_REVERSE_ALLSUFFIXES,
		ObservationTableCEXHandlers.RIVEST_SCHAPIRE,
		ObservationTableCEXHandlers.RIVEST_SCHAPIRE_ALLSUFFIXES
	};
	
	public static <I,D,M extends UniversalDeterministicAutomaton<?, I, ?, ?, ?>> void testLearnModel(UniversalDeterministicAutomaton<?, I, ?, ?, ?> target,
			Alphabet<I> alphabet,
			LearningAlgorithm<M, I, D> learner,
			MembershipOracle<I, D> oracle,
			EquivalenceOracle<? super M, I, D> eqOracle) {
		int maxRounds = target.size();
		
		learner.startLearning();
		
		while(maxRounds-- > 0) {
			M hyp = learner.getHypothesisModel();
			
			DefaultQuery<I, D> ce = eqOracle.findCounterExample(hyp, alphabet);
			
			if(ce == null)
				break;
			
			
			Assert.assertNotEquals(maxRounds, 0);
			
			learner.refineHypothesis(ce);
		}
		
		M hyp = learner.getHypothesisModel();
		
		Assert.assertEquals(hyp.size(), target.size());
	}

}