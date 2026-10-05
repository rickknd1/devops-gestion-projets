package tn.esprit.backend.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.esprit.backend.entity.Entreprise;
import tn.esprit.backend.repository.EntrepriseRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Test unitaire : le repository est simulé (Mockito), aucune base de données nécessaire
@ExtendWith(MockitoExtension.class)
class EntrepriseServiceImplTest {

    @Mock
    EntrepriseRepository entrepriseRepository;

    @InjectMocks
    EntrepriseServiceImpl entrepriseService;

    @Test
    void addEntreprise_enregistreEtRenvoieLEntreprise() {
        Entreprise entreprise = Entreprise.builder().nom("ESPRIT").adresse("Tunis").build();
        when(entrepriseRepository.save(entreprise)).thenReturn(entreprise);

        assertThat(entrepriseService.addEntreprise(entreprise).getNom()).isEqualTo("ESPRIT");
        verify(entrepriseRepository).save(entreprise);
    }

    @Test
    void getEntrepriseById_renvoieNullSiAbsente() {
        when(entrepriseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(entrepriseService.getEntrepriseById(99L)).isNull();
    }

    @Test
    void getAllEntreprises_renvoieLaListe() {
        when(entrepriseRepository.findAll()).thenReturn(List.of(new Entreprise(), new Entreprise()));

        assertThat(entrepriseService.getAllEntreprises()).hasSize(2);
    }

    @Test
    void deleteEntreprise_appelleLeRepository() {
        entrepriseService.deleteEntreprise(1L);

        verify(entrepriseRepository).deleteById(1L);
    }
}
