package ch.heigvd.pro.b04.android.question

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import ch.heigvd.pro.b04.android.datamodel.Answer
import ch.heigvd.pro.b04.android.datamodel.Question
import ch.heigvd.pro.b04.android.network.*
import ch.heigvd.pro.b04.android.network.RockinAPI.Companion.voteForAnswerSuspending
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class QuestionViewModel(application: Application, question : Question, private val token : String)
        : RequestsViewModel(application, question.idModerator.toInt(), question.idPoll.toInt(), token) {

    private val previousQuestion : MutableStateFlow<Question?> = MutableStateFlow(null)
    private val nextQuestion : MutableStateFlow<Question?> = MutableStateFlow(null)
    private val nbCheckedAnswer : MutableLiveData<Int> = MutableLiveData(0)
    private val networkErrors : Flow<NetworkError>

    private var lastVoteAtTime : Long = System.currentTimeMillis()

    val currentQuestion : MutableStateFlow<Question?> = MutableStateFlow(null)
    val answers: Flow<List<Answer>>

    init {
        currentQuestion.value = question

        val pollingAnswers : Flow<Pair<Long, Response<List<Answer>>>> = flow {
            while(true) {
                try {
                    currentQuestion.value?.let {
                        emit(System.currentTimeMillis() to RockinAPI.getAnswersSuspending(it, token))
                    }
                } catch (any : Exception) {}
                delay(DELAY)
            }
        }.broadcastIn(viewModelScope).asFlow()

        val pollingAnswerDelayed = pollingAnswers
            .filter { it.first > lastVoteAtTime + REFRESH_DELAY }
            .map { it.second }

        val answersUpdate : Flow<Response<List<Answer>>> = currentQuestion
            .filterNotNull()
            .map { RockinAPI.getAnswersSuspending(it, token) }
            .catch {}
            .filterNotNull()

        val requestAnswers = merge(pollingAnswerDelayed, answersUpdate)

        answers = requestAnswers.keepBody()
        networkErrors = merge(requestAnswers.keepError(), super.networkErrors())

        val questionRelative : Flow<Pair<Question, List<Question>>> = currentQuestion
            .filterNotNull()
            .zip(questions) { x, y -> x to y }

        viewModelScope.launch {
            questionRelative.map { (current, all) ->
                var candidate: Question? = null
                var candidateIndex = Double.MIN_VALUE

                all.forEach {
                    val newIndex = it.indexInPoll
                    if (newIndex < current.indexInPoll && newIndex > candidateIndex) {
                        candidate = it
                        candidateIndex = newIndex
                    }
                }

                return@map candidate
            }.collect {
                previousQuestion.value = it
            }
        }

        viewModelScope.launch {
            questionRelative.map { (current, all) ->
                var candidate: Question? = null
                var candidateIndex = Double.MAX_VALUE

                all.forEach {
                    val newIndex = it.indexInPoll
                    if (newIndex > current.indexInPoll && newIndex < candidateIndex) {
                        candidate = it
                        candidateIndex = newIndex
                    }
                }

                return@map candidate
            }.collect {
                nextQuestion.value = it
            }
        }
    }

    fun selectAnswer(answer: Answer) {
        val question: Question? = currentQuestion.value

        if (question == null || question.idQuestion != answer.idQuestion)
            return

        var counter: Int = nbCheckedAnswer.value!!

        if (question.answerMax > counter || question.answerMax == 0 || answer.isChecked) {

            if (answer.isChecked) {
                counter++
            } else {
                counter--
            }
            nbCheckedAnswer.value = counter
            lastVoteAtTime = System.currentTimeMillis()

            answer.toggle()
            // Note that for now, we do not take the result into account
            viewModelScope.launch {
                voteForAnswerSuspending(answer, token)
            }
        }
    }

    fun changeToPreviousQuestion() : Unit {
        currentQuestion.value = previousQuestion.value
    }

    fun changeToNextQuestion() : Unit {
        currentQuestion.value = nextQuestion.value
    }

    fun getNbCheckedAnswer() : LiveData<Int> {
        return nbCheckedAnswer
    }

    override fun networkErrors(): Flow<NetworkError> {
        return networkErrors
    }

    companion object {
        private const val REFRESH_DELAY :Long = 5000
    }
}