package com.company.docai.app.service.tour;

import com.company.docai.app.service.dashboard.DashboardService;
import com.company.docai.app.service.dashboard.UntypedDocuments;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Finds the documents of the tour by the fields of their type: the ids change with every load of a demo set, and
 * the names with its language.
 */
@Component
public class TourDocuments {

    private static final String LEASE_QUERY = """
            select d from Document d
            where exists (select v from dynat_CategoryAttributeValue v where v.entity.entityId = d.id
                          and v.parent is null and v.code = 'contractNumber' and v.stringValue = '112')
              and exists (select v from dynat_CategoryAttributeValue v where v.entity.entityId = d.id
                          and v.parent is null and v.code = 'contractKind' and v.stringValue = 'lease')
            order by d.createdDate desc""";

    private final DashboardService dashboardService;
    private final FolderService folderService;
    private final DataManager dataManager;

    public TourDocuments(DashboardService dashboardService, FolderService folderService, DataManager dataManager) {
        this.dashboardService = dashboardService;
        this.folderService = folderService;
        this.dataManager = dataManager;
    }

    /**
     * Returns the untyped document with the id while the user may open it.
     */
    public Optional<Document> untyped(UUID id) {
        return dataManager.load(Document.class)
                .query("select d from Document d where d.id = :id and d.category is null")
                .parameter("id", id)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME))
                .optional();
    }

    /**
     * Returns the scan of the second chapter: the first untyped document the dashboard offers to fill with AI.
     */
    public Optional<Document> scan() {
        return dashboardService.untypedDocuments()
                .latest()
                .stream()
                .filter(UntypedDocuments.Row::fillable)
                .map(UntypedDocuments.Row::document)
                .findFirst();
    }

    /**
     * Returns the lease of the fourth chapter.
     */
    public Optional<Document> lease() {
        // documents, so that their access rule decides; a query of the values would be refused for an employee
        return dataManager.load(Document.class)
                .query(LEASE_QUERY)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME))
                .maxResults(1)
                .optional();
    }

    /**
     * Returns the folder of the first chapter: the scan's, or the root.
     */
    public Folder uploadFolder() {
        return scan()
                .map(Document::getFolder)
                .orElseGet(folderService::getRoot);
    }
}
