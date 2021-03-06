package fr.abes.theses.tasklets;

import fr.abes.theses.components.FichierResultat;
import fr.abes.theses.model.dto.NoticeBiblioDto;
import fr.abes.theses.service.ServiceProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class GenererFichierTasklet implements Tasklet, StepExecutionListener
{
    private Integer jobId;

    @Autowired
    @Getter
    ServiceProvider service;

    @Value("${files.upload.path}")
    private String uploadPath;

    private List<NoticeBiblioDto> noticeBiblioDtos;

    public GenererFichierTasklet() {
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        jobId = stepExecution.getJobExecutionId().intValue();
        this.noticeBiblioDtos = getService().getNoticeBiblioService().getAllNoticesByJobId(jobId).stream().map(n -> new NoticeBiblioDto(n)).collect(Collectors.toList());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        return null;
    }

    @Override
    public RepeatStatus execute(StepContribution stepContribution, ChunkContext chunkContext)  {

        FichierResultat fichierResultat = new FichierResultat();
        fichierResultat.generateFileName(this.jobId);
        fichierResultat.setPath(Paths.get(uploadPath));

        try (FileWriter fw = new FileWriter(fichierResultat.getPath().resolve(fichierResultat.getFilename()).toString(), false);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {

            out.println("idJob;idDoc;idCodeEtab;trait??e;retourSudoc");
            for (NoticeBiblioDto noticeBiblioDto: this.noticeBiblioDtos) {
                out.println(noticeBiblioDto.getIdJob() + ";"
                        + noticeBiblioDto.getIddoc() + ";"
                        + noticeBiblioDto.getCodeEtab() + ";"
                        + noticeBiblioDto.getDone() + ";"
                        + noticeBiblioDto.getRetourSudoc()
                );
            }
        }
        catch (IOException e) {
            log.error("Impossible de g??n??rer le fichier r??sultat : " + e.toString());
            stepContribution.setExitStatus(ExitStatus.FAILED);
        }
        return RepeatStatus.FINISHED;
    }
}
