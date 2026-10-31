package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

public enum AppointmentState {
    AGENDADA{
        @Override public boolean isBussy(){
            return  true;
        }
    },
    ATENDIDA{
        @Override public boolean isBussy(){
            return  true;
        }
    },
    CANCELADA{
        @Override public boolean isBussy(){
            return  false;
        }
    },
    NO_ASISTIO{
        @Override public boolean isBussy(){
            return  false;
        }
    },
    REPROGRAMADA{
        @Override public boolean isBussy(){
            return  false;
        }
    };

    public abstract boolean isBussy();
}
