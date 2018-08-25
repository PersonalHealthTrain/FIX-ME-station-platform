package de.difuture.ekut.pht.station

import com.spotify.docker.client.DefaultDockerClient
import de.difuture.ekut.pht.lib.registry.docker.BasicAuth
import de.difuture.ekut.pht.lib.registry.docker.DefaultDockerRegistryClient
import de.difuture.ekut.pht.lib.registry.train.DefaultTrainRegistryClient
import de.difuture.ekut.pht.lib.registry.train.RunAlgorithmFailed
import de.difuture.ekut.pht.lib.registry.train.api.IDockerTrainArrival
import de.difuture.ekut.pht.lib.registry.train.api.RunInfo
import de.difuture.ekut.pht.lib.registry.train.tag.ITrainTag
import de.difuture.ekut.pht.lib.registry.train.tag.ModeTrainTag
import de.difuture.ekut.pht.lib.runtime.docker.DockerRunClient
import de.difuture.ekut.pht.station.persistence.TrainArrivalBeingProcessed
import de.difuture.ekut.pht.station.props.StationProperties
import de.difuture.ekut.pht.station.props.StationRegistryProperties
import de.difuture.ekut.pht.station.persistence.TrainArrivalsBeingProcessedRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlinx.coroutines.experimental.*

@Service
class TrainProcessor
@Autowired constructor(
        private val repository : TrainArrivalsBeingProcessedRepository,
        stationProps: StationProperties,
        registryProps: StationRegistryProperties) {


    // The train Registry that this station uses
    private val trainRegistry = DefaultTrainRegistryClient(
            DefaultDockerRegistryClient(
                    registryProps.uri,
                    HttpGetClientImpl(),
                    BasicAuth(registryProps.username, registryProps.password)), registryProps.namespace)

    // The TrainTag that the station will use to check in processed trains
    private val stationTag = ITrainTag.of(stationProps.id)

    // The Run Info for immediate Trains
    val runInfo  = RunInfo(stationProps.id.toInt(), ModeTrainTag.IMMEDIATE)

    private val dockerClient : DockerRunClient
    init {

        // The spotify Docker Client
        val spotifyDockerClient = DefaultDockerClient.fromEnv().build()

        // The Regular Docker Client
        val regularDockerClient = de.difuture.ekut.pht.dockerclient.DefaultDockerClient(spotifyDockerClient)

        // TODO These need to be filled if the Network or warnings or interrupt should be handled
        this.dockerClient = DockerRunClient(
                regularDockerClient,
                stationProps.resources,
                null,
                null,
                null)
    }


    private suspend fun processTrainArrival(arrival: IDockerTrainArrival, id: Int) {

        try {

            val departure =  arrival.runAlgorithm(this.dockerClient, this.runInfo)

            // TODO Audit
            // Push the departure back
            this.trainRegistry.push(departure)

        } catch (ex : RunAlgorithmFailed) {

            // TODO Implement error handling here
            println(ex.ontainerOutput)

            // Remove the failed contaier
            this.dockerClient.rm(ex.ontainerOutput.containerId)

            // This will result in an automatic retry we the Algorithm failed
            this.repository.deleteById(id)
        }
    }

    @Scheduled(fixedDelay = 5000L)
    fun processImmediateTags() {
        
         // Process all the train arrivals that the station sees in the train registry
         val arrivals = trainRegistry
                         .listTrainArrivals(ModeTrainTag.IMMEDIATE)
                         .filter {  !hasBeenProcessed(it) && !isCurrentlyBeingProcessed(it) }
                         .map { Pair(it, repository.save(TrainArrivalBeingProcessed(0, it.trainId)).id ) }

         arrivals.map { (arrival, id) ->

         	launch(CommonPool) {

         		 processTrainArrival(arrival, id)
         	}
         }
    }


    /**
     * Checks whether the train Arrival has already been processed by the station.
     * This works by checking the Train Registry if there alreay
     *
     */
    private fun hasBeenProcessed(arrival: IDockerTrainArrival) =
            this.trainRegistry.hasTrainArrival(arrival.trainId, stationTag)

    private fun isCurrentlyBeingProcessed(arrival: IDockerTrainArrival) =
            this.repository.existsByTrainId(arrival.trainId)
}
