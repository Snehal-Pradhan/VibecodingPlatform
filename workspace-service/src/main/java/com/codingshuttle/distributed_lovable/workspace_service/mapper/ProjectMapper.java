package com.codingshuttle.distributed_lovable.workspace_service.mapper;

import com.codingshuttle.distributed_lovable.common_lib.enums.ProjectRole;
import com.codingshuttle.distributed_lovable.workspace_service.dto.project.ProjectResponse;
import com.codingshuttle.distributed_lovable.workspace_service.dto.project.ProjectSummaryResponse;
import com.codingshuttle.distributed_lovable.workspace_service.entity.Project;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    ProjectResponse toProjectResponse(Project project);

    ProjectSummaryResponse toProjectSummaryResponse(Project project, ProjectRole role);

    List<ProjectSummaryResponse> toListOfProjectSummaryResponse(List<Project> projects);

}
