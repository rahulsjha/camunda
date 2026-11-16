/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {useState} from 'react';
import {useMatchRoute} from '@tanstack/react-router';
import {useTranslation} from 'react-i18next';
import {GitBranch, History, LayoutDashboard, Layers, Settings2, Workflow} from '@camunda/design-system/icons';
import type {SidebarNode} from '@camunda/design-system';
import type {AuthenticatedCurrentUser} from '@camunda/camunda-api-zod-schemas/8.11';
import {hasComponentAccess} from '#/shared/componentAccess';

function useOperateSidebarNavigation({authorizedComponents}: AuthenticatedCurrentUser): SidebarNode[] {
	const {t} = useTranslation();
	const matchRoute = useMatchRoute();
	const isProcessesRoute = matchRoute({to: '/operate/processes', fuzzy: true}) !== false;
	const isDecisionsRoute = matchRoute({to: '/operate/decisions', fuzzy: true}) !== false;
	const isOperationsLogRoute = matchRoute({to: '/operate/operations-log', fuzzy: true}) !== false;
	const isBatchOperationsRoute = matchRoute({to: '/operate/batch-operations', fuzzy: true}) !== false;
	const isOperationsRoute = isOperationsLogRoute || isBatchOperationsRoute;
	const [expansion, setExpansion] = useState({isOperationsRoute, isExpanded: isOperationsRoute});
	const isOperationsExpanded =
		expansion.isOperationsRoute === isOperationsRoute ? expansion.isExpanded : isOperationsRoute;

	if (!hasComponentAccess('operate', authorizedComponents)) {
		return [];
	}

	return [
		{
			type: 'item',
			key: 'dashboard',
			label: t('operate.dashboard.title'),
			icon: LayoutDashboard,
			isActive: !isProcessesRoute && !isDecisionsRoute && !isBatchOperationsRoute && !isOperationsLogRoute,
			linkProps: {to: '/operate', activeOptions: {exact: true}},
		},
		{
			type: 'item',
			key: 'processes',
			label: t('operate.processes.title'),
			icon: Workflow,
			isActive: isProcessesRoute,
			linkProps: {
				to: '/operate/processes',
				search: {active: true, suspended: true, incidents: true},
				state: {operateHideOptionalFilters: true},
			},
		},
		{
			type: 'item',
			key: 'decisions',
			label: t('operate.decisions.title'),
			icon: GitBranch,
			isActive: isDecisionsRoute,
			linkProps: {
				to: '/operate/decisions',
				search: {evaluated: true, failed: true},
				state: {operateHideOptionalFilters: true},
			},
		},
		{
			type: 'group',
			key: 'operations',
			label: t('operate.operations.title'),
			icon: Settings2,
			isExpanded: isOperationsExpanded,
			onToggleExpand: (isExpanded) => setExpansion({isOperationsRoute, isExpanded}),
			children: [
				{
					type: 'item',
					key: 'batch-operations',
					label: t('operate.batchOperations.title'),
					icon: Layers,
					isActive: isBatchOperationsRoute,
					linkProps: {to: '/operate/batch-operations'},
				},
				{
					type: 'item',
					key: 'operations-log',
					label: t('operate.operationsLog.title'),
					icon: History,
					isActive: isOperationsLogRoute,
					linkProps: {to: '/operate/operations-log'},
				},
			],
		},
	];
}

export {useOperateSidebarNavigation};
